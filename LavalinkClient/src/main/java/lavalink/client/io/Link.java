/*
 * Copyright (c) 2017 Frederik Ar. Mikkelsen & NoobLance
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package lavalink.client.io;

import lavalink.client.player.LavalinkPlayer;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.entities.VoiceChannel;
import net.dv8tion.jda.core.entities.impl.JDAImpl;
import net.dv8tion.jda.core.events.guild.voice.GuildVoiceMoveEvent;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Indicates which node we are linked to, what voice channel to use, and what player we are using
 */
public class Link {

    private static final Logger log = LoggerFactory.getLogger(Link.class);
    private static final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    /**
     * Time before we forcefully reconnect if we don't receive our leave event
     */
    private static final int TIMEOUT_MS = 5000;

    private final Lavalink lavalink;
    private final String guild;
    private LavalinkPlayer player;
    /**
     * Channel we are currently connected to or disconnecting/connecting from/to, if any
     */
    private volatile String currentChannel = null;
    /**
     * The node we are currently connected to. Automatically assigned.
     * Can only be reassigned by reconnecting any existing voice connection.
     */
    private volatile LavalinkSocket currentNode = null;
    private volatile LavalinkSocket pendingNode = null;

    /* May only be set by setState() */
    private volatile State state = State.NO_CHANNEL;


    /**
     * Used for making sure we properly time out disconnect attempts
     */
    private final AtomicInteger disconnectCounter = new AtomicInteger(0);
    private final AtomicInteger connectCounter = new AtomicInteger(0);

    Link(Lavalink lavalink, String guildId) {
        this.lavalink = lavalink;
        this.guild = guildId;
    }

    public LavalinkPlayer getPlayer() {
        if (player == null) {
            player = new LavalinkPlayer(this);
        }

        return player;
    }

    public void resetPlayer() {
        player = null;
    }

    public Lavalink getLavalink() {
        return lavalink;
    }

    public String getGuildId() {
        return guild;
    }

    /**
     * Eventually connect to a channel. Takes care of disconnecting from an existing connection
     *
     * @param channel Channel to connect to
     */
    @SuppressWarnings("WeakerAccess")
    public void connect(VoiceChannel channel) {
        if (currentNode == null) {
            currentNode = lavalink.loadBalancer.determineBestSocket(channel.getGuild().getIdLong());
        }
        connectNow(channel.getId());
    }

    /**
     * Invoked when we are ready too establish a connection
     *
     * @param channelId Channel to connect to
     */
    private void connectNow(String channelId) {
        if (state == State.DESTROYED) {
            log.warn("Attempted to connect to vc for guild " + guild + " while Link is destroyed. Ignoring...");
            return;
        }

        int startCount = connectCounter.get();

        executor.schedule(() -> {
            if (startCount == connectCounter.get()) {
                log.warn("Connecting timed out for guild " + guild + ". Forcefully sending OP 4...");
                forcefullyDisconnect();
            }
        }, TIMEOUT_MS, TimeUnit.MILLISECONDS);

        VoiceChannel channel = getJda()
                .getVoiceChannelById(channelId);

        assert channel.getGuild().getId().equals(guild);

        currentChannel = channelId;
        setState(State.CONNECTING);

        JSONObject json = new JSONObject();
        json.put("op", "connect");
        json.put("guildId", channel.getGuild().getId());
        json.put("channelId", channel.getId());
        currentNode.send(json.toString());
    }

    public void disconnect() {
        // Make sure we eventually reconnect
        int startCount = disconnectCounter.get();

        if (currentNode == null) {
            log.warn("Current node is somehow null while we are trying to disconnect! " +
                    "Did someone try to disconnect before connecting in the first place? " +
                    "Guild: " + guild + " State: " + state);
            setState(State.NO_CHANNEL);
            return;
        }

        executor.schedule(() -> {
            if (startCount == disconnectCounter.get()) {
                // We didn't get the leave event, so we *must* not be connected
                log.warn("Attempted to disconnect from voice but timed out after " + TIMEOUT_MS
                        + "ms. Are we already disconnected?" +
                        " Pretending that we left so we don't get stuck... Guild: " + guild);
                forcefullyDisconnect();
                onVoiceLeave();
            }
        }, TIMEOUT_MS, TimeUnit.MILLISECONDS);

        if (state == State.NO_CHANNEL) {
            log.info("Attempt to disconnect from channel when not connected. Guild: " + guild);
            sendDisconnectOp();
            return;
        }

        if (state != State.DESTROYED)
            setState(State.DISCONNECTING);

        sendDisconnectOp();
    }

    private void sendDisconnectOp() {
        JSONObject json = new JSONObject();
        json.put("op", "disconnect");
        json.put("guildId", guild);
        currentNode.send(json.toString());
    }

    void changeNode(LavalinkSocket newNode) {
        if (state == State.NO_CHANNEL) {
            changeNode0(newNode);
        } else {
            // Since we are connected, we will need to disconnect before truly changing node
            pendingNode = newNode;
            sendDisconnectOp();
        }
    }

    /**
     * Disconnects the voice connection (if any) and internally dereferences this {@link Link}.
     * <p>
     * You should invoke this method your bot leaves a {@link net.dv8tion.jda.core.entities.Guild}.
     */
    @SuppressWarnings("unused")
    public void destroy() {
        log.debug("Destroying Link for " + guild);
        lavalink.removeDestroyedLink(this);
        setState(State.DESTROYED);
        if (state != State.NO_CHANNEL) {
            try {
                disconnect();
            } catch (Exception e) {
                // Shouldn't happen. This is to prevent a regression of a previous bug
                log.error("Caught exception while trying to disconnect a destroyed link!", e);
            }
        }
    }

    private void changeNode0(LavalinkSocket newNode) {
        currentNode = newNode;
        pendingNode = null;
        VoiceChannel currentChannel = getCurrentChannel();
        if (currentChannel != null) {
            connect(currentChannel);
            getPlayer().onNodeChange();
        }
    }

    @Deprecated
    @SuppressWarnings("WeakerAccess")
    @Nullable
    public LavalinkSocket getCurrentSocket() {
        return currentNode;
    }

    /**
     * @return The current node
     */
    @Nullable
    @SuppressWarnings({"WeakerAccess", "unused"})
    public LavalinkSocket getNode() {
        return getNode(false);
    }

    /**
     * @param selectIfAbsent If true determines a new socket if there isn't one yet
     * @return The current node
     */
    @Nullable
    @SuppressWarnings("WeakerAccess")
    public LavalinkSocket getNode(boolean selectIfAbsent) {
        if (selectIfAbsent && currentNode == null) {
            currentNode = lavalink.loadBalancer.determineBestSocket(Long.parseLong(guild));
        }
        return currentNode;
    }

    void onVoiceJoin() {
        connectCounter.incrementAndGet();
        setState(State.CONNECTED);
    }

    void onVoiceLeave() {
        disconnectCounter.incrementAndGet();

        if (pendingNode != null) {
            // Disconnecting means we can change to the pending node, if any
            changeNode0(pendingNode);
        } else {
            setState(State.NO_CHANNEL);
            currentChannel = null;
        }
    }

    void onGuildVoiceMove(GuildVoiceMoveEvent event) {
        connectCounter.incrementAndGet();
        log.info("Moved from " + event.getChannelLeft() + " to " + event.getChannelJoined());
        currentChannel = event.getChannelJoined().getId();
    }

    void onNodeDisconnected() {
        changeNode(lavalink.loadBalancer.determineBestSocket(Long.parseLong(this.guild)));

        // This will trigger a leave
        forcefullyDisconnect();
    }

    /**
     * This sends OP 4 to Discord. This should close any voice connection for our guild by setting the channel to null.
     */
    private void forcefullyDisconnect() {
        ((JDAImpl) getJda()).getClient()
                .send("{\"op\":4,\"d\":{\"self_deaf\":false,\"guild_id\":\"" + guild + "\",\"channel_id\":null,\"self_mute\":false}}");
    }

    /**
     * @return The channel we are currently connect to, even if we are trying to connect to a different one
     */
    @SuppressWarnings("WeakerAccess")
    public VoiceChannel getCurrentChannel() {
        if (currentChannel == null) return null;

        return getJda()
                .getVoiceChannelById(currentChannel);
    }

    public JDA getJda() {
        return lavalink.getJdaFromSnowflake(guild);
    }

    /**
     * @return The {@link State} of this {@link Link}
     */
    public State getState() {
        return state;
    }

    private void setState(State state) {
        if (this.state == State.DESTROYED && state != State.DESTROYED)
            throw new IllegalStateException("Cannot change state to " + state + " when state is " + State.DESTROYED);

        log.debug("Link {} changed state from {} to {}", this, this.state, state);
        this.state = state;
    }

    @Override
    public String toString() {
        return "Link{" +
                "guild='" + guild + '\'' +
                ", currentChannel='" + currentChannel + '\'' +
                ", state=" + state +
                '}';
    }

    public enum State {
        /**
         * Default, means we are not trying to use voice at all
         */
        NO_CHANNEL,

        /**
         * Connecting to voice
         */
        CONNECTING,

        /**
         * Connected (or attempting to reconnect) to voice
         */
        CONNECTED,

        /**
         * We are trying to disconnect, after which we will switch to NO_CHANNEL
         */
        DISCONNECTING,
        /**
         * This {@link Link} has been destroyed and will soon (if not already) be unmapped from {@link Lavalink}
         */
        DESTROYED
    }

}
