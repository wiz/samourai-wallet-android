package com.samourai.wallet.whirlpool;

import com.google.gson.Gson;
import com.samourai.whirlpool.client.mix.transport.IWhirlpoolStompClient;
import com.samourai.whirlpool.protocol.WhirlpoolProtocol;

import org.reactivestreams.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.websocket.MessageHandler;

import io.reactivex.Completable;
import io.reactivex.CompletableSource;
import io.reactivex.CompletableTransformer;
import io.reactivex.Flowable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.functions.Action;
import io.reactivex.functions.Consumer;
import io.reactivex.schedulers.Schedulers;
import ua.naiksoftware.stomp.LifecycleEvent;
import ua.naiksoftware.stomp.Stomp;
import ua.naiksoftware.stomp.client.StompClient;
import ua.naiksoftware.stomp.client.StompMessage;

public class AndroidWhirlpoolStompClient implements IWhirlpoolStompClient {
    private Logger log = LoggerFactory.getLogger(AndroidWhirlpoolStompClient.class.getSimpleName());
    private static final String HEADER_DESTINATION = "destination";
    private static final long TIMEOUT = 20000;
    private Gson gson;
    private StompClient stompClient;

    public AndroidWhirlpoolStompClient() {
        this.gson = new Gson();
    }

    @Override
    public void connect(String url, Map<String, String> stompHeaders, final MessageHandler.Whole<String> onConnect, final MessageHandler.Whole<Throwable> onDisconnect) {
        try {
            log.info("connecting to " + url);
            stompClient = Stomp.over(Stomp.ConnectionProvider.JWS, url);
            stompClient.lifecycle()
                    .timeout(TIMEOUT, TimeUnit.MILLISECONDS, new Flowable<LifecycleEvent>() {
                        @Override
                        protected void subscribeActual(Subscriber<? super LifecycleEvent> s) {
                            log.error("timeout");
                            disconnect();
                            onDisconnect.onMessage(new Exception("disconnected"));
                        }
                    })
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new Consumer<LifecycleEvent>() {
                        @Override
                        public void accept(LifecycleEvent lifecycleEvent) {
                            log.info("connect accept: "+lifecycleEvent.getMessage());
                            switch (lifecycleEvent.getType()) {
                                case OPENED:
                                    log.info("connected");
                                    String stompUsername = "foo"; // TODO
                                    onConnect.onMessage(stompUsername);
                                    break;
                                case ERROR:
                                    log.error("Stomp connection error", lifecycleEvent.getException());
                                    break;
                                case CLOSED:
                                    log.info("disconnected");
                                    disconnect();
                                    onDisconnect.onMessage(new Exception("disconnected"));
                            }
                        }
                    });
        }catch(Exception e) {
            log.error("connect error", e);
            onDisconnect.onMessage(new Exception("connect error"));
            throw e;
        }
    }

    @Override
    public String getSessionId() {
        return null; // TODO
    }

    @Override
    public void subscribe(Map<String, String> stompHeaders, final MessageHandler.Whole<Object> onMessage, MessageHandler.Whole<String> onError) {
        try {
            String destination = getDestination(stompHeaders);
            log.info("subscribing " + destination);
            stompClient.topic(destination)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new Consumer<StompMessage>() {
                        @Override
                        public void accept(StompMessage stompMessage) throws Exception {
                            log.info("subscribe accept");
                            String messageType = stompMessage.findHeader(WhirlpoolProtocol.HEADER_MESSAGE_TYPE);
                            log.info("messageType" + messageType);
                            String jsonPayload = stompMessage.getPayload();
                            Object whirlpoolMessage = gson.fromJson(jsonPayload, Class.forName(messageType));
                            onMessage.onMessage(whirlpoolMessage);
                        }
                    });
        }
        catch (Exception e) {
            log.error("subscribe error", e);
        }
        log.info("subscribed");
    }

    @Override
    public void send(Map<String, String> stompHeaders, Object payload) {
        try {
            String destination = getDestination(stompHeaders);
            log.info("sending " + destination);
            String jsonPayload = gson.toJson(payload);
            stompClient.send(destination, jsonPayload)
                    .compose(applySchedulers())
                    .subscribe(new Action() {
                        @Override
                        public void run() throws Exception {
                            log.info("send: success");
                        }
                    }, new Consumer<Throwable>() {
                        @Override
                        public void accept(Throwable throwable) throws Exception {
                            log.info("send: error", throwable);
                        }
                    });
        } catch(Exception e) {
            log.error("send error", e);
        }
    }

    @Override
    public void disconnect() {
        if (stompClient != null) {
            try {
                stompClient.disconnect();
            } catch(Exception e) {}
        }
    }

    private String getDestination(Map<String, String> stompHeaders) {
        return stompHeaders.get(HEADER_DESTINATION);
    }

    private CompletableTransformer applySchedulers() {
        return new CompletableTransformer() {
            @Override
            public CompletableSource apply(Completable upstream) {
                return upstream
                        .unsubscribeOn(Schedulers.newThread())
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread());
            }
        };
    }

    /*@Override // TODO
    protected void onDestroy() {
        stompClient.disconnect();
        if (mRestPingDisposable != null) mRestPingDisposable.dispose();
        super.onDestroy();
    }*/
}
