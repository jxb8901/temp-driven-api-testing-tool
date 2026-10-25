package com.ibm.mq;

import java.util.Hashtable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Test double for the reflective IBM MQ constructor used by IbmMqClientFactory. */
public final class MQQueueManager {
    public static volatile CountDownLatch firstConnect=new CountDownLatch(0);
    public static volatile CountDownLatch secondConnect=new CountDownLatch(0);
    public static volatile CountDownLatch releaseFirstConnect=new CountDownLatch(0);
    public static final List<String[]> observedTimeouts=new CopyOnWriteArrayList<>();
    private static final AtomicInteger calls=new AtomicInteger();
    private static volatile boolean blockFirst;

    public MQQueueManager(String queueManager,Hashtable<?,?> properties) throws InterruptedException {
        int call=calls.incrementAndGet();
        observedTimeouts.add(new String[]{
                System.getProperty("com.ibm.mq.cfg.TCP.Connect_Timeout"),
                System.getProperty("com.ibm.mq.cfg.TCP.QmgrNegotiationFlowTimeout")});
        if(call==1) {
            firstConnect.countDown();
            if(blockFirst)releaseFirstConnect.await(5,TimeUnit.SECONDS);
        } else if(call==2)secondConnect.countDown();
    }

    public static void reset(boolean block) {
        observedTimeouts.clear();calls.set(0);blockFirst=block;
        firstConnect=new CountDownLatch(1);secondConnect=new CountDownLatch(1);releaseFirstConnect=new CountDownLatch(1);
    }

    public void disconnect() { }
}
