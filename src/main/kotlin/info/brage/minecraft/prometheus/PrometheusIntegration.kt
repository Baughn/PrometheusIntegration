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
import net.minecraft.world.ChunkCoordIntPair
import net.minecraft.world.World
import net.minecraftforge.common.ForgeChunkManager
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.common.config.Configuration
import net.minecraftforge.common.config.Property
import net.minecraftforge.event.terraingen.ChunkProviderEvent
import net.minecraftforge.event.world.ChunkEvent
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.ServletContextHandler
import org.eclipse.jetty.servlet.ServletHolder
import java.awt.image.BufferedImage
import java.awt.image.RenderedImage
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import javax.imageio.ImageIO
import javax.servlet.http.HttpServlet
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse


const val MODID = "prometheus-integration"
const val VERSION = "1.0"

var jettyPort = 0

// All in ticks.
val chunkLoadAgeTime = 3600 * 20
val chunkMinimumForceTime = 2700 * 20
val chunkMaximumForceTime = 3600 * 20

val server: MinecraftServer by lazy {
    MinecraftServer.getServer()
}

@Mod(modid = MODID, version = VERSION, acceptedMinecraftVersions = "[1.7.10,)", acceptableRemoteVersions = "*")
class PrometheusIntegration {

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

        // Setup handlers.
        val chunkManager = ChunkManager(this)
        context.addServlet(ServletHolder(ChunkMapper(chunkManager)), "/chunkmap/*")
        val eventHandler = EventHandler(chunkManager)

        // Connect to FML.
        MinecraftForge.EVENT_BUS.register(eventHandler)
        FMLCommonHandler.instance().bus().register(eventHandler)
        //MinecraftForge.TERRAIN_GEN_BUS.register(events);
        ForgeChunkManager.setForcedChunkLoadingCallback(this, chunkManager)
    }
}

// TODO: Actual UI.
class ChunkMapper(val chunkManager: ChunkManager) : HttpServlet() {
    private val mapUrl = Regex("/(-?[0-9]+).png")

    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        println(req)
        val path = req.pathInfo

        if (path.matches(mapUrl)) {
            resp.contentType = "image/png"
            val w = mapUrl.matchEntire(path)!!.destructured.component1().toInt()
            val image = buildImage(w)
            ImageIO.write(image, "png", resp.outputStream)
        } else {
            resp.sendError(500)
        }
    }

    private val emptyImage = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)

    private fun buildImage(world: Int): RenderedImage {
        val coords = chunkManager.chunks.filterKeys { it.w == world }.keys.toList()
        if (coords.size == 0)
            return emptyImage

        // Find image limits
        var minx = coords[0].xz.chunkXPos
        var minz = coords[0].xz.chunkZPos
        var maxx = minx
        var maxz = minz
        for (c in coords) {
            val x = c.xz.chunkXPos
            val z = c.xz.chunkZPos
            if (x < minx) minx = x
            if (x > maxx) maxx = x
            if (z < minz) minz = z
            if (z > maxz) maxz = z
        }
        val w = maxx - minx + 1
        val h = maxz - minz + 1

        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)

        for (c in coords) {
            val x = c.xz.chunkXPos
            val z = c.xz.chunkZPos
            image.setRGB(x - minx, z - minz, 0x00ff0000)
        }
        return image
    }
}

data class ChunkCoord(val xz: ChunkCoordIntPair, val w: Int)

data class Chunk(val ticket: ForgeChunkManager.Ticket, val unloadTime: Int, val coord: ChunkCoord)

class ChunkManager(val mod: PrometheusIntegration) : ForgeChunkManager.LoadingCallback {
    // Not terribly useful yet, since it just loads everything.
    val chunkChurnPatched = Gauge.build().name("chunk_churn_patched").help("Chunks churning, and temporarily chunkloaded").register()

    init {
        // Basically infinity. There's no benefit to having a limit here.
        ForgeChunkManager.addConfigProperty(mod, "maximumChunksPerTicket", "1000000", Property.Type.INTEGER)
        ForgeChunkManager.addConfigProperty(mod, "maximumTicketCount", "1000000", Property.Type.INTEGER)
    }

    override fun ticketsLoaded(p0: MutableList<ForgeChunkManager.Ticket>?, p1: World?) {
        // We don't care. Do nothing on restart.
    }

    // Currently forced chunks.
    val chunks = ConcurrentHashMap<ChunkCoord, Chunk>()
    val chunksByWorld = HashMap<World, Int>()
    val unloadSchedule = PriorityQueue<Chunk>(Comparator<Chunk> { a, b -> a.unloadTime - b.unloadTime })

    // Chunks that may or may not be loaded right now.
//    val unloadTime = HashMap<ChunkCoord, Int>()
    val loadTimes = HashMap<ChunkCoord, LinkedList<Int>>()
    val tesByChunk = HashMap<ChunkCoord, Int>()

    fun onChunkLoad(e: ChunkEvent.Load) {
        // Look for chunks to unload.
        val now = server.tickCounter
        while (unloadSchedule.size > 0 && unloadSchedule.peek().unloadTime <= now) {
            val chunk = unloadSchedule.remove()
            chunks.remove(chunk.coord)
            chunksByWorld[e.world] = chunksByWorld.getOrDefault(e.world, 1)
            ForgeChunkManager.releaseTicket(chunk.ticket)
            chunkChurnPatched.dec()
        }

        // Should we load this new one?
        val xz = e.chunk.chunkCoordIntPair
        val xzw = ChunkCoord(xz, e.world.provider.dimensionId)
        if (chunks.containsKey(xzw)) return
        if (ForgeChunkManager.getPersistentChunksFor(e.world).containsKey(xz)) return
        // TODO: Maybe allow if it it's next to a chunk with TEs?
        val tecount = e.chunk.chunkTileEntityMap.size
        tesByChunk[xzw] = tecount
        if (tecount == 0 && !hasNearbyTE(tecount, xzw)) return
//        if (unloadTime.getOrDefault(xzw, 0) < now - chunkChurningThreshold) return

        // Force the chunk.
        val loads = loadTimes.getOrPut(xzw, { LinkedList() })
        ageLoads(loads, now)
        val loadCount = loads.size
        println("$xzw, $loadCount")
//        val unloadTimeCoef = when {
//            now < 20*600 -> 0.1f
//            loadCount < 5 -> 0.1f
//            else -> (loadCount - 5f) / 20f + 0.1f
//        }
        val unloadTimeCoef = 1f
        if (unloadTimeCoef == 0f) return
        val unloadTime = (now + unloadTimeCoef * ThreadLocalRandom.current().nextInt(chunkMinimumForceTime, chunkMaximumForceTime)).toInt()
        val ticket = ForgeChunkManager.requestTicket(mod, e.world, ForgeChunkManager.Type.NORMAL)
        if (ticket == null) {
            println("Failed to force chunk")
            return
        }
        ForgeChunkManager.forceChunk(ticket, xzw.xz)
        chunkChurnPatched.inc()
        val chunk = Chunk(ticket, unloadTime, xzw)
        chunks[xzw] = chunk
        unloadSchedule.add(chunk)
        chunksByWorld[e.world] = chunksByWorld.getOrDefault(e.world, 0)
        loads.add(now)
        return
    }

    private fun hasNearbyTE(tecount: Int, xzw: ChunkCoord): Boolean {
        if (tecount == 0) {
            for (x in -2..2) {
                for (y in -2..2) {
                    // No TEs in this chunk, but if there are TEs nearby then force anyway.
                    if (tesByChunk.getOrDefault(xzw, 0) > 0) return true
                }
            }
        }
        return false
    }

    private fun ageLoads(loads: LinkedList<Int>, now: Int) {
        val threshold = now - chunkLoadAgeTime
        while (!loads.isEmpty() && loads.first < threshold) loads.removeFirst()
    }

    fun onChunkUnload(e: ChunkEvent.Unload) {
//        val coord = ChunkCoord(e.chunk.chunkCoordIntPair, e.world.provider.dimensionId)
//        unloadTime[coord] = server.tickCounter
    }
}

class EventHandler(val chunkManager: ChunkManager) {
    // Tick rate.
    val ticks = Counter.build().name("ticks").help("Server ticks").register()
    val tickTime = Summary.build().name("tick_time").help("Server tick time").register()
    val worldTickTime = Summary.build().name("world_time").labelNames("world").help("World tick time").register()
    // World generation.
    val worldgen = Counter.build().name("worldgen").help("Chunks generated").register()
    // Players.
    val present = Gauge.build().name("present").labelNames("name").help("Is player present").register()
    // Possible load causes.
    val chunksLoaded = Gauge.build().name("chunks_loaded").labelNames("cause", "world")
            .help("Count of chunks loaded, by cause").register()
    val chunkLoad = Counter.build().name("chunk_load").help("Chunk load rate").register()
    val chunkUnload = Counter.build().name("chunk_unload").help("Chunk unload rate").register()

    var serverTimer: Summary.Timer? = null
    var worldTimer: Summary.Timer? = null
    var serverTicks = 0

    @SubscribeEvent fun onChunkLoad(event: ChunkEvent.Load) {
        chunkManager.onChunkLoad(event)
        chunkLoad.inc()
    }

    @SubscribeEvent fun onChunkUnload(event: ChunkEvent.Unload) {
        chunkManager.onChunkUnload(event)
        chunkUnload.inc()
    }

    private fun setLoadedChunks() {
        for (world in server.worldServers) {
            val total = world.chunkProvider.loadedChunkCount
            val totalForced = ForgeChunkManager.getPersistentChunksFor(world).size()
            val forcedByUs = chunkManager.chunksByWorld.getOrDefault(world, 0)
            val forcedByOthers = totalForced - forcedByUs
            chunksLoaded.labels("chunkloader", world.provider.dimensionName).set(forcedByOthers.toDouble())
            chunksLoaded.labels("chunkchurn", world.provider.dimensionName).set(forcedByUs.toDouble())
            chunksLoaded.labels("other", world.provider.dimensionName).set((total - totalForced).toDouble())
        }
    }

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

        when (serverTicks % 60) {
//            17 -> unChurnChunks()
            27 -> setLoadedChunks()
            47 -> setPlayers()
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

    fun setPlayers() {
        val seenNow = HashSet<String>()

        // Set all new players' values to 1.
        for (world in server.worldServers) {
            for (obj in world.playerEntities) {
                if (obj is EntityPlayer) {
                    val name = obj.displayName
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
