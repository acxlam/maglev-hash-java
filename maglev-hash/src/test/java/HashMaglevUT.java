import acx.lam.Cell;
import acx.lam.HashMaglev16Balancer;
import com.google.common.collect.ComparisonChain;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.ThreadLocalRandom;

public class HashMaglevUT {
	
    private static final Logger logger = LoggerFactory.getLogger(HashMaglevUT.class);

    public static class ServerInstance implements Cell {

        public int ipaddress;
        public int port;
        
		public static final Comparator<ServerInstance> comparator = new Comparator<ServerInstance>() {

			@Override
			public int compare(ServerInstance left, ServerInstance right) {
				return ComparisonChain.start().compare(left.ipaddress, right.ipaddress).compare(left.port, right.port)
						.result();
			}

		};

        @Override
        public String getUniqueKey() {
            return String.format("%d.%d.%d.%d",(ipaddress & 0xff), (ipaddress >> 8 & 0xff), (ipaddress >> 16 & 0xff), (ipaddress >> 24 & 0xff)) + ":" + port;
        }

    }

    private static final int SERVER_SIZE = 511;

    private static final int DELTA = 1;


    @Test
    public void test() throws  Exception{

        final ThreadLocalRandom r = ThreadLocalRandom.current();

        final ArrayList<ServerInstance> instances = new ArrayList<ServerInstance>(SERVER_SIZE + 10);

        for (int i = 0 ; i < SERVER_SIZE ; ++i){
            instances.add(new ServerInstance(){{
                this.ipaddress = r.nextInt();
                this.port = r.nextInt(65536);
            }});

        }

        HashMaglev16Balancer<ServerInstance> hashMaglev16 = new HashMaglev16Balancer<>(instances, null, ServerInstance.comparator);
        hashSample(hashMaglev16);

        Collections.sort(instances,ServerInstance.comparator);

        hashMaglev16.addCells(new ArrayList<ServerInstance>(){{

            for (int i = 0; i < DELTA; i++) {
                ServerInstance instance = new ServerInstance(){{
                    this.ipaddress = r.nextInt();
                    this.port = r.nextInt(65536);
                }};
                this.add(instance);
            }

        }});

        hashSample(hashMaglev16);

        hashMaglev16.removeCells(new ArrayList<ServerInstance>(){{

            for (int i = 0; i < DELTA; i++) {
                this.add(instances.get(r.nextInt(instances.size())));
            }

        }});

        hashSample(hashMaglev16);

        logger.info("==========================================");

    }


    private void hashSample(HashMaglev16Balancer<ServerInstance> maglevBalancerX) {
        logger.info("ACX instance:" + maglevBalancerX.getInstance("ACX").getUniqueKey());
        logger.info("ACX instance:" + maglevBalancerX.getInstance("ACX").getUniqueKey());

        logger.info("LAM instance:" + maglevBalancerX.getInstance("LAM").getUniqueKey());
        logger.info("LAM instance:" + maglevBalancerX.getInstance("LAM").getUniqueKey());
    }
}
