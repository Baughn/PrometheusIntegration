package info.brage.minecraft.prometheus

import io.prometheus.client.Counter
import io.prometheus.client.Gauge
import io.prometheus.client.Summary
import io.prometheus.client.exporter.HTTPServer
import io.prometheus.client.hotspot.DefaultExports
import net.minecraft.entity.player.EntityPlayer
import net.minecraftforge.common.ForgeChunkManager
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.common.config.Configuration
import net.minecraftforge.event.terraingen.PopulateChunkEvent
import net.minecraftforge.fml.common.FMLCommonHandler
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.common.event.FMLInitializationEvent
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import java.util.*


const val MODID = "prometheus-integration"
const val VERSION = "1.0"

@Mod(modid = MODID, version = VERSION, acceptedMinecraftVersions="[1.10.2,)", acceptableRemoteVersions = "*")
class PrometheusIntegration {

    private var httpPort: Int = 0

    @Mod.EventHandler
    fun preInit(event: FMLPreInitializationEvent) {
        val config = Configuration(event.suggestedConfigurationFile)
        config.load()
        httpPort = config.getInt("jetty", "ports", 1234, 1025, 32767, "Port to run the metrics-server on.")
        config.save()
    }

    @Mod.EventHandler
    fun init(event: FMLInitializationEvent) {
        val server = HTTPServer(httpPort, true)

        // Get some Hotspot stats.
        DefaultExports.initialize()

        // Connect to Forge.
        MinecraftForge.EVENT_BUS.register(eventHandler)
        MinecraftForge.TERRAIN_GEN_BUS.register(eventHandler)
    }
}


object eventHandler {
    // Tick rate.
    val ticks = Counter.build().name("ticks").help("Server ticks").register()
    val tickTime = Summary.build().name("tick_time").help("Server tick time")
        .quantile(0.5, 0.05)
        .quantile(0.9, 0.01)
        .quantile(0.95, 0.01)
        .register()
    val worldTickTime = Summary.build().name("world_time").labelNames("world").help("World tick time")
        .quantile(0.5, 0.05)
        .quantile(0.9, 0.01)
        .quantile(0.95, 0.01)
        .register()
    // World generation.
    val worldgen = Counter.build().name("worldgen").help("Chunks generated").register()
    // Players.
    val present = Gauge.build().name("present").labelNames("name").help("Is player present").register()
    // Possible load causes.
    val chunksLoaded = Gauge.build().name("chunks_loaded").labelNames("cause", "world")
            .help("Count of chunks loaded, by cause").register()

    val server by lazy {
        FMLCommonHandler.instance().minecraftServerInstance
    }

    var serverTimer: Summary.Timer? = null
    var worldTimer: Summary.Timer? = null

    var serverTicks = 0

    private fun setLoadedChunks() {
        for (world in server.worlds) {
            val total = world.chunkProvider.loadedChunkCount
            val forced = ForgeChunkManager.getPersistentChunksFor(world).size()
            val name = world.provider.dimensionType.getName()
            chunksLoaded.labels("chunkloader", name).set(forced.toDouble())
            chunksLoaded.labels("other", name).set((total - forced).toDouble())
        }
    }

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        serverTicks++

        if (event.phase == TickEvent.Phase.START) {
            serverTimer = tickTime.startTimer()
        } else if (event.phase == TickEvent.Phase.END) {
            serverTimer!!.observeDuration()
            serverTimer = null
            ticks.inc()
        }

        when (serverTicks % 60) {
            27 -> setLoadedChunks()
            47 -> setPlayers()
        }
    }

    @SubscribeEvent
    fun onWorldTick(event: TickEvent.WorldTickEvent) {
        if (event.phase == TickEvent.Phase.START) {
            worldTimer = worldTickTime.labels(event.world.provider.dimensionType.getName()).startTimer()
        } else if (event.phase == TickEvent.Phase.END) {
            worldTimer!!.observeDuration()
            worldTimer = null
        }
    }

    @SubscribeEvent
    fun onWorldGen(event: PopulateChunkEvent.Pre) {
        worldgen.inc()
    }

    var seenPreviously = HashSet<String>()

    fun setPlayers() {
        val seenNow = HashSet<String>()

        // Set all new players' values to 1.
        for (world in server.worlds) {
            for (obj in world.playerEntities) {
                if (obj is EntityPlayer) {
                    val name = obj.displayNameString
                    seenNow.add(name)
                    if (!seenPreviously.contains(name)) {
                        present.labels(name).set(1.0)
                    }
                }
            }
        }
        // Delete all gone-away players.
        for (name in seenPreviously) {
            if (!seenNow.contains(name)) {
                present.remove(name)
            }
        }
        seenPreviously = seenNow
    }

}
