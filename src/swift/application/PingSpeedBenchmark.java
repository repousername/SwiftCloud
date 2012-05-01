package swift.application;

import java.util.logging.Logger;

import swift.client.SwiftImpl;
import swift.crdt.interfaces.CachePolicy;
import swift.crdt.interfaces.IsolationLevel;
import swift.dc.DCConstants;
import sys.Sys;

/**
 * Local setup/test with one server and two clients.
 * 
 * @author annettebieniusa
 * 
 */
public class PingSpeedBenchmark {
    private static String dcName;
    private static int clientId;
    private static Logger logger = Logger.getLogger("swift.application");

    public static void main(String[] args) {
        if (args.length != 6) {
            System.out
                    .println("Usage: [surrogate address] [number of iterations] [client id (1|2)] [isolationLevel] [cachePolicy] [notifications (true|false)]");
            return;
        } else {
            dcName = args[0];
            PingSpeedTest.iterations = Integer.parseInt(args[1]);
            clientId = Integer.parseInt(args[2]);
            PingSpeedTest.isolationLevel = IsolationLevel.valueOf(args[3]);
            PingSpeedTest.cachePolicy = CachePolicy.valueOf(args[4]);
            PingSpeedTest.notifications = Boolean.parseBoolean(args[5]);
        }

        System.out.print("PingSpeedBenchmark ");
        for (String s : args) {
            System.out.print(s + " ");
        }
        System.out.println("");

        logger.info("Initializing the system");
        Sys.init();
        SwiftImpl swift = SwiftImpl.newInstance(dcName, DCConstants.SURROGATE_PORT);

        if (clientId == 1) {
            logger.info("Starting client 1");
            PingSpeedTest.runClient1(swift);
        } else if (clientId == 2) {
            logger.info("Starting client 2");
            PingSpeedTest.runClient2(swift);
        }
    }
}