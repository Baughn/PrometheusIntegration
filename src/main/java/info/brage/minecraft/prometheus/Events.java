package info.brage.minecraft.prometheus;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.Summary;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraftforge.event.terraingen.ChunkProviderEvent;

import java.util.HashSet;

/**
 * Created by svein on 25/04/15.
 */
public class Events {
    static final Counter ticks = Counter.build()
            .name("ticks").help("Server ticks").register();
    static final Summary tickTime = Summary.build()
            .name("tick_time").help("Server tick time").register();
    static final Summary worldTickTime = Summary.build()
            .name("world_time").labelNames("world").help("World tick time").register();
    static final Counter worldgen = Counter.build()
            .name("worldgen").help("Chunks generated").register();
    static final Gauge present = Gauge.build()
            .name("present").labelNames("name").help("Is player present").register();

    Summary.Timer serverTimer;
    Summary.Timer worldTimer;

    int serverTicks = 0;

    @SubscribeEvent(receiveCanceled = true)
    public void onServerTick(TickEvent.ServerTickEvent event) {
        serverTicks++;

        if (event.phase == TickEvent.Phase.START) {
            serverTimer = tickTime.startTimer();
        } else if (event.phase == TickEvent.Phase.END) {
            serverTimer.observeDuration();
            serverTimer = null;
            ticks.inc();
        }

        if (serverTicks % 60 == 0) {
            setPlayers(MinecraftServer.getServer().getEntityWorld());
        }
    }

    @SubscribeEvent(receiveCanceled = true)
    public void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            worldTimer = worldTickTime.labels(event.world.provider.getDimensionName()).startTimer();
        } else if (event.phase == TickEvent.Phase.END) {
            worldTimer.observeDuration();
            worldTimer = null;
        }
    }

    @SubscribeEvent(receiveCanceled = true)
    public void onWorldGen(ChunkProviderEvent.ReplaceBiomeBlocks event) {
        worldgen.inc();
    }

    HashSet<String> seenPreviously = new HashSet<String>();

    void setPlayers(World world) {
        HashSet<String> seenNow = new HashSet<String>();

        // Set all new players' values to 1.
        for (Object obj : world.playerEntities) {
            if (obj instanceof EntityPlayer) {
                EntityPlayer player = (EntityPlayer)obj;
                String name = player.getDisplayName();
                seenNow.add(name);
                if (!seenPreviously.contains(name)) {
                    present.labels(name).set(1);
                }
            }
        }
        // Delete all gone-away players.
        for (String name : seenPreviously) {
            if (!seenNow.contains(name)) {
                present.remove(name);
            }
        }
        seenPreviously = seenNow;
    }
}
