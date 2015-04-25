package info.brage.minecraft.prometheus;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import io.prometheus.client.exporter.MetricsServlet;
import io.prometheus.client.hotspot.DefaultExports;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

@Mod(modid = PrometheusIntegration.MODID, version = PrometheusIntegration.VERSION, acceptedMinecraftVersions="[1.7.2,)", acceptableRemoteVersions = "*")
public class PrometheusIntegration
{
    public static final String MODID = "prometheus-integration";
    public static final String VERSION = "1.0";

    int jettyPort;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        Configuration config = new Configuration(event.getSuggestedConfigurationFile());
        config.load();
        jettyPort = config.getInt("jetty", "ports", 1234, 1025, 32767, "Port to run the metrics-server on.");
        config.save();
    }
    
    @Mod.EventHandler
    public void init(FMLInitializationEvent event) throws Exception
    {
        // Get port config.


        // Start up Jetty.
        Server server = new Server(jettyPort);
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        server.setHandler(context);
        context.addServlet(new ServletHolder(new MetricsServlet()), "/metrics");
        server.start();

        // Get some Hotspot stats.
        DefaultExports.initialize();

        // Connect to FML.
        MinecraftForge.EVENT_BUS.register(new Events());
        Events events = new Events();
        FMLCommonHandler.instance().bus().register(events);
        //MinecraftForge.TERRAIN_GEN_BUS.register(events);
    }
}

