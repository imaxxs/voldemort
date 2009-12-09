/*
 * Copyright 2009 LinkedIn, Inc
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package voldemort.cluster.failuredetector;

import static voldemort.MutableStoreResolver.createMutableStoreResolver;
import static voldemort.VoldemortTestConstants.getNineNodeCluster;

import java.util.concurrent.CountDownLatch;

import voldemort.cluster.Cluster;
import voldemort.cluster.Node;
import voldemort.utils.Time;

import com.google.common.collect.Iterables;

public class TimedUnavailabilityTest extends FailureDetectorPerformanceTest {

    private final long unavailabilityMillis;

    private TimedUnavailabilityTest(long unavailabilityMillis) {
        this.unavailabilityMillis = unavailabilityMillis;
    }

    public static void main(String[] args) throws Throwable {
        Cluster cluster = getNineNodeCluster();

        FailureDetectorConfig failureDetectorConfig = new FailureDetectorConfig().setNodes(cluster.getNodes())
                                                                                 .setStoreResolver(createMutableStoreResolver(cluster.getNodes()))
                                                                                 .setAsyncScanInterval(5000)
                                                                                 .setNodeBannagePeriod(5000);

        Class<?>[] classes = new Class[] { AsyncRecoveryFailureDetector.class,
                BannagePeriodFailureDetector.class, ThresholdFailureDetector.class };

        for(Class<?> implClass: classes) {
            failureDetectorConfig.setImplementationClassName(implClass.getName());
            new TimedUnavailabilityTest(2522).run(failureDetectorConfig);
        }
    }

    @Override
    public String test(final FailureDetector failureDetector, final Time time) throws Exception {
        FailureDetectorConfig failureDetectorConfig = failureDetector.getConfig();
        Node node = Iterables.get(failureDetectorConfig.getNodes(), 0);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        Listener listener = new Listener(time);
        failureDetector.addFailureDetectorListener(listener);

        Thread nodeAvailabilityThread = new Thread(new XXX(failureDetector, node, countDownLatch));
        Thread nodeAccessorThread = new Thread(new NodeAccessorRunnable(failureDetector,
                                                                        node,
                                                                        countDownLatch,
                                                                        null,
                                                                        null,
                                                                        null,
                                                                        10,
                                                                        10));

        nodeAvailabilityThread.start();
        nodeAccessorThread.start();

        nodeAvailabilityThread.join();
        nodeAccessorThread.join();

        return Class.forName(failureDetectorConfig.getImplementationClassName()).getSimpleName()
               + ", " + listener.getDelta();
    }

    private class XXX implements Runnable {

        private final FailureDetector failureDetector;

        private final Node node;

        private final CountDownLatch countDownLatch;

        public XXX(FailureDetector failureDetector, Node node, CountDownLatch countDownLatch) {
            this.failureDetector = failureDetector;
            this.node = node;
            this.countDownLatch = countDownLatch;
        }

        public void run() {
            FailureDetectorConfig failureDetectorConfig = failureDetector.getConfig();

            try {
                updateNodeStoreAvailability(failureDetectorConfig, node, false);
                failureDetectorConfig.getTime().sleep(unavailabilityMillis);
                updateNodeStoreAvailability(failureDetectorConfig, node, true);

                failureDetector.waitForAvailability(node);

                countDownLatch.countDown();
            } catch(Exception e) {
                e.printStackTrace();
            }
        }

    }

    private static class Listener implements FailureDetectorListener {

        private final Time time;

        private long markedAvailable;

        private long markedUnavailable;

        public Listener(Time time) {
            this.time = time;
        }

        public void nodeAvailable(Node node) {
            markedAvailable = time.getMilliseconds();
        }

        public void nodeUnavailable(Node node) {
            markedUnavailable = time.getMilliseconds();
        }

        public long getDelta() {
            return markedAvailable - markedUnavailable;
        }

    }

}