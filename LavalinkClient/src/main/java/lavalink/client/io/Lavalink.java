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

import lavalink.client.LavalinkUtil;
import lavalink.client.player.LavalinkPlayer;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.entities.Guild;
import net.dv8tion.jda.core.entities.VoiceChannel;
import net.dv8tion.jda.core.entities.impl.JDAImpl;
import net.dv8tion.jda.core.events.DisconnectEvent;
import net.dv8tion.jda.core.events.ReadyEvent;
import net.dv8tion.jda.core.events.ReconnectedEvent;
import net.dv8tion.jda.core.events.ResumedEvent;
import net.dv8tion.jda.core.events.ShutdownEvent;
import net.dv8tion.jda.core.events.guild.voice.GuildVoiceJoinEvent;
import net.dv8tion.jda.core.events.guild.voice.GuildVoiceLeaveEvent;
import net.dv8tion.jda.core.events.guild.voice.GuildVoiceMoveEvent;
import net.dv8tion.jda.core.hooks.ListenerAdapter;
import org.java_websocket.drafts.Draft_6455;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class Lavalink extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(Lavalink.class);

    private boolean autoReconnect = true;
    private final int numShards;
    private final Function<Integer, JDA> jdaProvider;
    private final ConcurrentHashMap<String, Link> links = new ConcurrentHashMap<>();
    private final String userId;
    final List<LavalinkSocket> nodes = new CopyOnWriteArrayList<>();
    final LavalinkLoadBalancer loadBalancer = new LavalinkLoadBalancer(this);

    private final ScheduledExecutorService reconnectService;

    public Lavalink(String userId, int numShards, Function<Integer, JDA> jdaProvider) {
        this.userId = userId;
        this.numShards = numShards;
        this.jdaProvider = jdaProvider;

        reconnectService = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "lavalink-reconnect-thread");
            thread.setDaemon(true);
            return thread;
        });
        reconnectService.scheduleWithFixedDelay(new ReconnectTask(this), 0, 500, TimeUnit.MILLISECONDS);
    }

    @SuppressWarnings("unused")
    public void setAutoReconnect(boolean autoReconnect) {
        this.autoReconnect = autoReconnect;
    }

    @SuppressWarnings("unused")
    public boolean getAutoReconnect() {
        return autoReconnect;
    }

    public void addNode(URI serverUri, String password) {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("Authorization", password);
        headers.put("Num-Shards", Integer.toString(numShards));
        headers.put("User-Id", userId);

        nodes.add(new LavalinkSocket(this, serverUri, new Draft_6455(), headers));
    }

    @SuppressWarnings("unused")
    public void removeNode(int key) {
        LavalinkSocket node = nodes.remove(key);
        node.close();
    }

    @SuppressWarnings("WeakerAccess")
    public Link getLink(String guildId) {
        return links.computeIfAbsent(guildId, __ -> new Link(this, guildId));
    }

    public LavalinkLoadBalancer getLoadBalancer() {
        return loadBalancer;
    }

    @SuppressWarnings("WeakerAccess")
    public Link getLink(Guild guild) {
        return getLink(guild.getId());
    }

    @SuppressWarnings("WeakerAccess")
    public int getNumShards() {
        return numShards;
    }

    @SuppressWarnings("WeakerAccess")
    public Collection<Link> getLinks() {
        return links.values();
    }

    @SuppressWarnings("WeakerAccess")
    public List<LavalinkSocket> getNodes() {
        return nodes;
    }

    @SuppressWarnings("WeakerAccess")
    public JDA getJda(int shardId) {
        return jdaProvider.apply(shardId);
    }

    @SuppressWarnings("WeakerAccess")
    public JDA getJdaFromSnowflake(String snowflake) {
        return jdaProvider.apply(LavalinkUtil.getShardFromSnowflake(snowflake, numShards));
    }

    public void shutdown() {
        reconnectService.shutdown();
        nodes.forEach(ReusableWebSocket::close);
    }

    void removeDestroyedLink(Link link) {
        log.info("Destroyed link for guild " + link.getGuildId());
        links.remove(link.getGuildId());
    }

    /*
     *  Deprecated, will be removed in v2.0
     */

    @Deprecated
    LavalinkSocket getSocket(String guildId) {
        return getLink(guildId).getNode(false);
    }

    @Deprecated
    public VoiceChannel getConnectedChannel(Guild guild) {
        return getLink(guild).getCurrentChannel();
    }

    @Deprecated
    public String getConnectedChannel(String guildId) {
        return getLink(guildId).getCurrentChannel().getId();
    }

    @Deprecated
    public LavalinkSocket getNodeForGuild(Guild guild) {
        return getLink(guild).getNode(false);
    }

    @Deprecated
    public LavalinkPlayer getPlayer(String guildId) {
        return getLink(guildId).getPlayer();
    }

    @Deprecated
    public void openVoiceConnection(VoiceChannel channel) {
        getLink(channel.getGuild()).connect(channel);
    }

    @Deprecated
    public void closeVoiceConnection(Guild guild) {
        getLink(guild).disconnect();
    }

    /*
     *  JDA event handling
     */

    @Override
    public void onReady(ReadyEvent event) {
        ((JDAImpl) event.getJDA()).getClient().getHandlers()
                .put("VOICE_SERVER_UPDATE", new VoiceServerUpdateInterceptor(this, (JDAImpl) event.getJDA()));
    }

    @Override
    public void onDisconnect(DisconnectEvent event) {
        disconnectVoiceConnection(event.getJDA());
    }

    @Override
    public void onShutdown(ShutdownEvent event) {
        disconnectVoiceConnection(event.getJDA());
    }

    @Override
    public void onReconnect(ReconnectedEvent event) {
        reconnectVoiceConnections(event.getJDA());
    }

    @Override
    public void onResume(ResumedEvent event) {
        reconnectVoiceConnections(event.getJDA());
    }

    @Override
    public void onGuildVoiceJoin(GuildVoiceJoinEvent event) {
        // Check if not ourselves
        if (!event.getMember().getUser().equals(event.getJDA().getSelfUser())) return;

        getLink(event.getGuild()).onVoiceJoin();
    }

    @Override
    public void onGuildVoiceLeave(GuildVoiceLeaveEvent event) {
        // Check if not ourselves
        if (!event.getMember().getUser().equals(event.getJDA().getSelfUser())) return;

        getLink(event.getGuild()).onVoiceLeave();
    }

    @Override
    public void onGuildVoiceMove(GuildVoiceMoveEvent event) {
        // Check if not ourselves
        if (!event.getMember().getUser().equals(event.getJDA().getSelfUser())) return;

        getLink(event.getGuild()).onGuildVoiceMove(event);
    }

    private void reconnectVoiceConnections(JDA jda) {
        if (autoReconnect) {
            links.forEach((guildId, link) -> {
                try {
                    //Note: We also ensure that the link belongs to the JDA object
                    if (link.getCurrentChannel() != null
                            && jda.getGuildById(guildId) != null) {
                        link.connect(link.getCurrentChannel());
                    }
                } catch (Exception e) {
                    log.error("Caught exception while trying to reconnect link " + link, e);
                }
            });
        }
    }

    private void disconnectVoiceConnection(JDA jda) {
        links.forEach((guildId, link) -> {
            try {
                if (jda.getGuildById(guildId) != null) {
                    link.disconnect();
                }
            } catch (Exception e) {
                log.error("Caught exception while trying to disconnect link " + link, e);
            }
        });
    }

}
