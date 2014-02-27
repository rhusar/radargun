package org.radargun;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

import org.radargun.config.Cluster;
import org.radargun.config.Configuration;
import org.radargun.config.Scenario;
import org.radargun.logging.Log;
import org.radargun.logging.LogFactory;
import org.radargun.stages.lifecycle.AbstractStartStage;
import org.radargun.stages.DefaultDistStageAck;
import org.radargun.state.SlaveState;

/**
 * Slave being coordinated by a single {@link Master} object in order to run benchmarks.
 *
 * @author Mircea.Markus@jboss.com
 */
public class Slave {

   private static Log log = LogFactory.getLog(Slave.class);

   private SlaveState state = new SlaveState();
   //private int slaveIndex = -1;
   //private int slaveCount;
   private RemoteMasterConnection connection;

   private Scenario scenario;
   private Configuration configuration;
   private Cluster cluster;

   public Slave(RemoteMasterConnection connection) {
      this.connection = connection;
      //this.slaveIndex = slaveIndex;
      Runtime.getRuntime().addShutdownHook(new ShutDownHook("Slave process"));
   }

   private void run(int slaveIndex) throws Exception {
      InetAddress address = connection.connectToMaster(slaveIndex);
      // the provided slaveIndex is just a "recommendation"
      state.setSlaveIndex(connection.receiveSlaveIndex());
      log.info("Received slave index " + state.getSlaveIndex());
      state.setMaxClusterSize(connection.receiveSlaveCount());
      log.info("Received slave count " + state.getMaxClusterSize());
      state.setLocalAddress(address);
      while (true) {
         Object object = connection.receiveObject();
         if (object == null) {
            log.info("Master shutdown!");
            break;
         } else if (object instanceof Scenario) {
            scenario = (Scenario) object;
         } else if (object instanceof Configuration) {
            configuration = (Configuration) object;
            state.setConfigName(configuration.name);
         } else if (object instanceof Cluster) {
            cluster = (Cluster) object;
            int stageId;
            Map<String, String> extras = getCurrentExtras(configuration, cluster);
            Cluster.Group group = cluster.getGroup(state.getSlaveIndex());
            state.setClusterSize(cluster.getSize());
            state.setGroupSize(group.size);
            state.setPlugin(configuration.getSetup(group.name).plugin);
            Configuration.Setup setup = configuration.getSetup(group.name);
            while ((stageId = connection.receiveNextStageId()) >= 0) {
               DistStage stage = (DistStage) scenario.getStage(stageId, extras);
               stage.initOnSlave(state);
               if (stage instanceof AbstractStartStage) {
                  ((AbstractStartStage) stage).setup(setup.service, setup.file, setup.getProperties());
               }
               DistStageAck response;
               try {
                  long start =System.currentTimeMillis();
                  response = stage.executeOnSlave();
                  response.setDuration(System.currentTimeMillis() - start);
               } catch (Exception e) {
                  log.error("Stage execution has failed", e);
                  response = new DefaultDistStageAck(state.getSlaveIndex(), state.getLocalAddress()).error("Stage execution has failed", e);
               }
               connection.sendReponse(response);
            }
            connection.sendReponse(new DefaultDistStageAck(state.getSlaveIndex(), state.getLocalAddress()));
         }
      }
      ShutDownHook.exit(0);
   }


   public static void main(String[] args) {
      String masterHost = null;
      int masterPort = RemoteSlaveConnection.DEFAULT_PORT;
      int slaveIndex = -1;
      for (int i = 0; i < args.length - 1; i++) {
         if (args[i].equals("-master")) {
            String param = args[i + 1];
            if (param.contains(":")) {
               masterHost = param.substring(0, param.indexOf(":"));
               try {
                  masterPort = Integer.parseInt(param.substring(param.indexOf(":") + 1));
               } catch (NumberFormatException nfe) {
                  log.warn("Unable to parse port part of the master!  Failing!");
                  ShutDownHook.exit(10);
               }
            } else {
               masterHost = param;
            }
         } else if (args[i].equals("-slaveIndex")) {            
            try {
               slaveIndex = Integer.parseInt(args[i + 1]);
            } catch (NumberFormatException nfe) {
               log.warn("Unable to parse slaveIndex!  Failing!");
               ShutDownHook.exit(10);
            }
         }
      }
      if (masterHost == null) {
         printUsageAndExit();
      }
      Slave slave = new Slave(new RemoteMasterConnection(masterHost, masterPort));
      try {
         slave.run(slaveIndex);
      } catch (Exception e) {
         e.printStackTrace();
         ShutDownHook.exit(10);
      }
   }

   private static void printUsageAndExit() {
      System.out.println("Usage: start_local_slave.sh -master <host>:port");
      System.out.println("       -master: The host(and optional port) on which the master resides. If port is missing it defaults to " + RemoteSlaveConnection.DEFAULT_PORT);
      ShutDownHook.exit(1);
   }

   private Map<String, String> getCurrentExtras(Configuration configuration, Cluster cluster) {
      Map<String, String> extras = new HashMap<String, String>();
      extras.put(Properties.PROPERTY_CONFIG_NAME, configuration.name);
      extras.put(Properties.PROPERTY_PLUGIN_NAME, state.getPlugin());
      extras.put(Properties.PROPERTY_CLUSTER_SIZE, String.valueOf(cluster.getSize()));
      extras.put(Properties.PROPERTY_CLUSTER_MAX_SIZE, String.valueOf(state.getMaxClusterSize()));
      extras.put(Properties.PROPERTY_SLAVE_INDEX, String.valueOf(state.getSlaveIndex()));
      Cluster.Group group = cluster.getGroup(state.getSlaveIndex());
      extras.put(Properties.PROPERTY_GROUP_NAME, group.name);
      extras.put(Properties.PROPERTY_GROUP_SIZE, String.valueOf(group.size));
      return extras;
   }
}
