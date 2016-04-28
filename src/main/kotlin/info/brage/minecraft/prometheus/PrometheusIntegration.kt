package info.brage.minecraft.prometheus

import cpw.mods.fml.common.FMLCommonHandler
import cpw.mods.fml.common.Mod
import cpw.mods.fml.common.event.FMLInitializationEvent
import cpw.mods.fml.common.event.FMLPreInitializationEvent
import cpw.mods.fml.common.eventhandler.SubscribeEvent
import cpw.mods.fml.common.gameevent.TickEvent
import io.prometheus.client.Counter
import io.prometheus.client.Gauge
import io.prometheus.client.Summary
import io.prometheus.client.exporter.MetricsServlet
import io.prometheus.client.hotspot.DefaultExports
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.server.MinecraftServer
import net.minecraft.world.World
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.common.config.Configuration
import net.minecraftforge.event.terraingen.ChunkProviderEvent
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.ServletContextHandler
import org.eclipse.jetty.servlet.ServletHolder
import java.util.*


const val MODID = "prometheus-integration"
const val VERSION = "1.0"

@Mod(modid = MODID, version = VERSION, acceptedMinecraftVersions="[1.7.10,)", acceptableRemoteVersions = "*")
class PrometheusIntegration {

    var jettyPort: Int = 0

    @Mod.EventHandler
    fun preInit(event: FMLPreInitializationEvent) {
        val config = Configuration(event.suggestedConfigurationFile)
        config.load()
        jettyPort = config.getInt("jetty", "ports", 1234, 1025, 32767, "Port to run the metrics-server on.")
        config.save()
    }

    @Mod.EventHandler
    fun init(event: FMLInitializationEvent) {
        // Start up Jetty.
        val server = Server(jettyPort)
        val context = ServletContextHandler()
        context.contextPath = "/"
        server.handler = context
        context.addServlet(ServletHolder(MetricsServlet()), "/metrics")
        server.start()

        // Get some Hotspot stats.
        DefaultExports.initialize()

        // Connect to FML.
        MinecraftForge.EVENT_BUS.register(eventHandler)
        FMLCommonHandler.instance().bus().register(eventHandler)
        //MinecraftForge.TERRAIN_GEN_BUS.register(events);
    }
}


object eventHandler {
    // Variables exposed.
    val ticks = Counter.build().name("ticks").help("Server ticks").register()
    val tickTime = Summary.build().name("tick_time").help("Server tick time").register()
    val worldTickTime = Summary.build().name("world_time").labelNames("world").help("World tick time").register()
    val worldgen = Counter.build().name("worldgen").help("Chunks generated").register()
    val present = Gauge.build().name("present").labelNames("name").help("Is player present").register()

    var serverTimer: Summary.Timer? = null
    var worldTimer: Summary.Timer? = null

    var serverTicks = 0


    @SubscribeEvent(receiveCanceled = true)
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        serverTicks++

        if (event.phase == TickEvent.Phase.START) {
            serverTimer = tickTime.startTimer()
        } else if (event.phase == TickEvent.Phase.END) {
            serverTimer!!.observeDuration()
            serverTimer = null
            ticks.inc()
        }

        if (serverTicks % 60 == 47) {
            setPlayers(MinecraftServer.getServer().entityWorld)
        }
    }

    @SubscribeEvent(receiveCanceled = true)
    fun onWorldTick(event: TickEvent.WorldTickEvent) {
        if (event.phase == TickEvent.Phase.START) {
            worldTimer = worldTickTime.labels(event.world.provider.dimensionName).startTimer()
        } else if (event.phase == TickEvent.Phase.END) {
            worldTimer!!.observeDuration()
            worldTimer = null
        }
    }

    @SubscribeEvent(receiveCanceled = true)
    fun onWorldGen(event: ChunkProviderEvent.ReplaceBiomeBlocks) {
        worldgen.inc()
    }

    var seenPreviously = HashSet<String>()

    fun setPlayers(world: World) {
        val seenNow = HashSet<String>()

        // Set all new players' values to 1.
        for (obj in world.playerEntities) {
            if (obj is EntityPlayer) {
                val name = obj.displayName
                seenNow.add(name)
                if (!seenPreviously.contains(name)) {
                    present.labels(name).set(1.0)
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
