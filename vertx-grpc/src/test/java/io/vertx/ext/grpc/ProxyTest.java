package io.vertx.ext.grpc;

import io.grpc.ManagedChannel;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.examples.helloworld.VertxGreeterGrpc;
import io.grpc.examples.streaming.Empty;
import io.grpc.examples.streaming.Item;
import io.grpc.examples.streaming.StreamingGrpc;
import io.grpc.examples.streaming.VertxStreamingGrpc;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServer;
import io.vertx.core.net.SocketAddress;
import io.vertx.core.streams.WriteStream;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.grpc.VertxChannelBuilder;
import io.vertx.grpc.server.GrpcClient;
import io.vertx.grpc.server.GrpcServer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 * @author <a href="mailto:plopes@redhat.com">Paulo Lopes</a>
 */
public class ProxyTest extends GrpcTestBase {

  private volatile ManagedChannel channel;

  @Override
  public void tearDown(TestContext should) {
    if (channel != null) {
      channel.shutdown();
    }
    super.tearDown(should);
  }

  @Test
  public void testUnary(TestContext should) {

    GrpcClient client = new GrpcClient(vertx);

    Future<HttpServer> server = vertx.createHttpServer().requestHandler(new GrpcServer().callHandler(GreeterGrpc.getSayHelloMethod(), call -> {
      call.handler(helloRequest -> {
        System.out.println("GOT REQ");
        HelloReply helloReply = HelloReply.newBuilder().setMessage("Hello " + helloRequest.getName()).build();
        call.response().end(helloReply);
      });
    })).listen(8080, "localhost");

    Future<HttpServer> proxy = vertx.createHttpServer().requestHandler(new GrpcServer().requestHandler(clientReq -> {
      client.request(SocketAddress.inetSocketAddress(8080, "localhost")).onComplete(should.asyncAssertSuccess(proxyReq -> {
        proxyReq.fullMethodName(clientReq.fullMethodName());
        clientReq.messageHandler(msg -> {
          System.out.println("MSG");
        });
        clientReq.endHandler(v -> proxyReq.end());
      }));
    })).listen(8081, "localhost");

    Async test = should.async();
    server.flatMap(v -> proxy).onComplete(should.asyncAssertSuccess(v -> {
      client.call(SocketAddress.inetSocketAddress(8081, "localhost"), GreeterGrpc.getSayHelloMethod())
        .onComplete(should.asyncAssertSuccess(callRequest -> {
          callRequest.response().onComplete(should.asyncAssertSuccess(callResponse -> {
            AtomicInteger count = new AtomicInteger();
            callResponse.messageHandler(reply -> {
              should.assertEquals(1, count.incrementAndGet());
              should.assertEquals("Hello Julien", reply.getMessage());
            });
            callResponse.endHandler(v2 -> {
              should.assertEquals(1, count.get());
              test.complete();
            });
          }));
          callRequest.end(HelloRequest.newBuilder().setName("Julien").build());
        }));
    }));
  }

  @Test
  public void testServerStreaming(TestContext should) throws Exception {

    int numItems = 128;

    Async test = should.async();

    GrpcServer service = new GrpcServer();
    service.callHandler(StreamingGrpc.getSourceMethod(), call -> {
      for (int i = 0;i < numItems;i++) {
        Item item = Item.newBuilder().setValue("the-value-" + i).build();
        call.response().write(item);
      }
      call.response().end();
    });

    vertx.createHttpServer().requestHandler(service).listen(8080, "localhost")
      .onComplete(should.asyncAssertSuccess(v -> {
        channel = VertxChannelBuilder.forAddress(vertx, "localhost", port)
          .usePlaintext()
          .build();

        VertxStreamingGrpc.StreamingVertxStub stub = VertxStreamingGrpc.newVertxStub(channel);
        final List<String> items = new ArrayList<>();
        stub.source(Empty.newBuilder().build())
          .endHandler(v1 -> {
            List<String> expected = IntStream.rangeClosed(0, numItems - 1).mapToObj(val -> "the-value-" + val).collect(Collectors.toList());
            should.assertEquals(expected, items);
            test.complete();
          })
          .exceptionHandler(should::fail)
          .handler(item -> items.add(item.getValue()));
      }));
  }

  @Test
  public void testClientStreaming(TestContext should) throws Exception {

    int numItems = 128;

    // Async test = should.async();

    GrpcServer service = new GrpcServer();
    service.callHandler(StreamingGrpc.getSinkMethod(), call -> {
      call.handler(item -> {
        // Should assert item
      });
      call.endHandler(v -> {
        call.response().end(Empty.getDefaultInstance());
      });
    });

    vertx.createHttpServer().requestHandler(service).listen(8080, "localhost")
      .onComplete(should.asyncAssertSuccess(v -> {
        channel = VertxChannelBuilder.forAddress(vertx, "localhost", port)
          .usePlaintext()
          .build();

        VertxStreamingGrpc.StreamingVertxStub stub = VertxStreamingGrpc.newVertxStub(channel);
        AtomicInteger count = new AtomicInteger(numItems);

        Handler<WriteStream<Item>> h = ws -> vertx.setPeriodic(10, id -> {
          int val = count.decrementAndGet();
          if (val >= 0) {
            ws.write(Item.newBuilder().setValue("the-value-" + (numItems - val - 1)).build());
          } else {
            vertx.cancelTimer(id);
            ws.end();
          }
        });

        stub.sink(h)
          .onComplete(should.asyncAssertSuccess());
      }));
  }

  @Test
  public void testBidiStreaming(TestContext should) throws Exception {

    int numItems = 128;

    Async test = should.async();

    GrpcServer service = new GrpcServer();
    service.callHandler(StreamingGrpc.getPipeMethod(), call -> {
      call.handler(item -> {
        call.response().write(item);
      });
      call.endHandler(v -> {
        call.response().end();
      });
    });

    vertx.createHttpServer().requestHandler(service).listen(8080, "localhost")
      .onComplete(should.asyncAssertSuccess(v -> {
        channel = VertxChannelBuilder.forAddress(vertx, "localhost", port)
          .usePlaintext()
          .build();
        VertxStreamingGrpc.StreamingVertxStub stub = VertxStreamingGrpc.newVertxStub(channel);
        final List<String> items = new ArrayList<>();
        AtomicInteger count = new AtomicInteger(numItems);

        Handler<WriteStream<Item>> h = ws -> vertx.setPeriodic(10, id -> {
          int val = count.decrementAndGet();
          if (val >= 0) {
            ws.write(Item.newBuilder().setValue("the-value-" + (numItems - val - 1)).build());
          } else {
            vertx.cancelTimer(id);
            ws.end();
          }
        });

        stub.pipe(h)
          .endHandler(v1 -> {
            List<String> expected = IntStream.rangeClosed(0, numItems - 1).mapToObj(val -> "the-value-" + val).collect(Collectors.toList());
            should.assertEquals(expected, items);
            test.complete();
          })
          .exceptionHandler(should::fail)
          .handler(item -> items.add(item.getValue()));
      }));
  }
}
