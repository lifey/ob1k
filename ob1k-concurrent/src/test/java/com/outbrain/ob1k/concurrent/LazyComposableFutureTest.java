package com.outbrain.ob1k.concurrent;

import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.outbrain.ob1k.concurrent.handlers.*;
import com.outbrain.ob1k.concurrent.lazy.LazyComposableFuture;
import org.junit.Assert;
import org.junit.Test;
import rx.Observable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by aronen on 2/3/15.
 */
public class LazyComposableFutureTest {

  @Test
  public void testFromValue() throws ExecutionException, InterruptedException {
    final ComposableFuture<Integer> res = LazyComposableFuture.fromValue(3);
    final Integer result = res.get();
    Assert.assertEquals(result, new Integer(3));
  }

  @Test
  public void testFromError() throws InterruptedException {
    final String errorMessage = "oh no...";
    final LazyComposableFuture<Object> res = LazyComposableFuture.fromError(new RuntimeException(errorMessage));

    res.consume(new Consumer<Object>() {
      @Override
      public void consume(final Try<Object> result) {
        System.out.println("got: " + result);
      }
    });

    try {
      final Object result = res.get();
      Assert.fail("should get an exception");
    } catch (final ExecutionException e) {
      final Throwable cause = e.getCause();
      Assert.assertTrue(cause != null);
      Assert.assertTrue(cause.getClass() == RuntimeException.class);
      Assert.assertTrue(cause.getMessage().equals(errorMessage));
    }
  }

  @Test
  public void testBuildFuture() throws ExecutionException, InterruptedException {
    final AtomicInteger producerCounter = new AtomicInteger();
    final String message = "great success";
    final ComposableFuture<String> res = LazyComposableFuture.build(new Producer<String>() {
      @Override
      public void produce(final Consumer<String> consumer) {
        producerCounter.incrementAndGet();
        consumer.consume(Try.fromValue(message));
      }
    });

    Thread.sleep(100);
    Assert.assertEquals(producerCounter.get(), 0);

    res.consume(new Consumer<String>() {
      @Override
      public void consume(final Try<String> result) {
        System.out.println("got: " + result);
      }
    });

    final String result = res.get();
    Assert.assertTrue(result.equals(message));
    Assert.assertEquals(producerCounter.get(), 2);
  }

  @Test
  public void testContinueOnSuccess() throws ExecutionException, InterruptedException {
    final ComposableFuture<String> res = LazyComposableFuture.fromValue("one").continueOnSuccess(new SuccessHandler<String, String>() {
      @Override
      public String handle(final String result) {
        return result + ",two";
      }
    }).continueOnSuccess(new FutureSuccessHandler<String, String>() {
      @Override
      public ComposableFuture<String> handle(final String result) {
        return LazyComposableFuture.fromValue(result + ",three");
      }
    });

    res.consume(new Consumer<String>() {
      @Override
      public void consume(final Try<String> result) {
        System.out.println("get: " + result);
      }
    });

    final String result = res.get();
    Assert.assertEquals(result, "one,two,three");
  }

  @Test
  public void testContinueOnFailure() throws ExecutionException, InterruptedException {
    final ComposableFuture<String> res1 = LazyComposableFuture.fromValue("one").continueOnError((ErrorHandler<String>) error -> "two");

    Assert.assertEquals(res1.get(), "one");

    final ComposableFuture<String> res2 = LazyComposableFuture.<String>fromError(new RuntimeException("bad start")).continueOnError(new ErrorHandler<String>() {
      @Override
      public String handle(final Throwable error) {
        return "ok";
      }
    });

    Assert.assertEquals(res2.get(), "ok");

    final ComposableFuture<String> res3 = LazyComposableFuture.<String>fromError(new RuntimeException("bad start")).continueOnError(new FutureErrorHandler<String>() {
      @Override
      public ComposableFuture<String> handle(final Throwable error) {
        return LazyComposableFuture.fromError(new RuntimeException("even worse"));
      }
    });

    try {
      res3.get();
      Assert.fail("should fail");
    } catch (final ExecutionException e) {
      final Throwable cause = e.getCause();
      Assert.assertTrue(cause != null);
      Assert.assertEquals(cause.getClass(), RuntimeException.class);
      Assert.assertEquals(cause.getMessage(), "even worse");
    }
  }

  @Test
  public void testSubmit() throws ExecutionException, InterruptedException {
    final AtomicInteger prodCounter = new AtomicInteger();
    final ExecutorService executor = Executors.newFixedThreadPool(1);
    final ComposableFuture<String> res = LazyComposableFuture.submit(executor, new Callable<String>() {
      @Override
      public String call() throws Exception {
        prodCounter.incrementAndGet();
        return "first";
      }
    }, false).continueOnSuccess(new FutureSuccessHandler<String, String>() {
      @Override
      public ComposableFuture<String> handle(final String result) {
        return LazyComposableFuture.submit(executor, new Callable<String>() {
          @Override
          public String call() throws Exception {
            prodCounter.incrementAndGet();
            return result + ",second";
          }
        }, false);
      }
    });

    res.consume(new Consumer<String>() {
      @Override
      public void consume(final Try<String> result) {
        System.out.println("got: " + result);
      }
    });

    final String result = res.get();
    Assert.assertEquals(result, "first,second");
    Assert.assertEquals(prodCounter.get(), 4);
    executor.shutdown();
  }

  @Test
  public void testSchedule() throws ExecutionException, InterruptedException {
    final Scheduler scheduler = new ThreadPoolBasedScheduler(1,"test-schedule");
    final ComposableFuture<Integer> res1 = LazyComposableFuture.schedule(scheduler, new Callable<Integer>() {
      @Override
      public Integer call() throws Exception {
        return 1;
      }
    }, 100, TimeUnit.MILLISECONDS);

    final ComposableFuture<Integer> res2 = LazyComposableFuture.schedule(scheduler, new Callable<Integer>() {
      @Override
      public Integer call() throws Exception {
        return 2;
      }
    }, 300, TimeUnit.MILLISECONDS);

    final ComposableFuture<Integer> res3 = LazyComposableFuture.schedule(scheduler, new Callable<Integer>() {
      @Override
      public Integer call() throws Exception {
        return 3;
      }
    }, 200, TimeUnit.MILLISECONDS);

    final ComposableFuture<List<Integer>> res = LazyComposableFuture.collectAll(Arrays.asList(res1, res2, res3));

    final List<Integer> result = res.get();
    Assert.assertEquals(result.size(), 3);
    Assert.assertEquals(result.get(0), new Integer(1));
    Assert.assertEquals(result.get(1), new Integer(2));
    Assert.assertEquals(result.get(2), new Integer(3));

  }

  @Test
  public void testWithTimeout() throws ExecutionException, InterruptedException {
    final Scheduler scheduler = new ThreadPoolBasedScheduler(1,"test-with-timeout");
    final LazyComposableFuture<String> fast = LazyComposableFuture.schedule(scheduler, new Callable<String>() {
      @Override
      public String call() throws Exception {
        return "fast";
      }
    }, 100, TimeUnit.MILLISECONDS).withTimeout(scheduler, 200, TimeUnit.MILLISECONDS);

    final String res1 = fast.get();
    Assert.assertEquals(res1, "fast");

    final LazyComposableFuture<String> slow = LazyComposableFuture.schedule(scheduler, new Callable<String>() {
      @Override
      public String call() throws Exception {
        return "slow";
      }
    }, 200, TimeUnit.MILLISECONDS).withTimeout(scheduler, 100, TimeUnit.MILLISECONDS);

    try {
      final String res2 = slow.get();
      Assert.fail("should have timed out");
    } catch (final ExecutionException e) {
      final Throwable cause = e.getCause();
      Assert.assertEquals(cause.getClass(), TimeoutException.class);
    }

    scheduler.shutdown();
  }

  @Test
  public void testDoubleDispatch() throws ExecutionException, InterruptedException {
    final ExecutorService executor = Executors.newFixedThreadPool(2);
    final Scheduler scheduler = new ThreadPoolBasedScheduler(1,"test-double-dispatch");

    final AtomicBoolean state1 = new AtomicBoolean(false);
    final LazyComposableFuture<String> res1 = LazyComposableFuture.submit(executor, new Callable<String>() {
      @Override
      public String call() throws Exception {
        if (state1.compareAndSet(false, true)) {
          Thread.sleep(200);
          return "first";
        } else {
          Thread.sleep(50);
          return "second";
        }
      }
    }, false).doubleDispatch(scheduler, 100, TimeUnit.MILLISECONDS);

    final long t1 = System.currentTimeMillis();
    final String result1 = res1.get();
    final long t2 = System.currentTimeMillis();

    Assert.assertEquals(result1, "second");
    Assert.assertTrue((t2 - t1) < 200);

    final AtomicBoolean state2 = new AtomicBoolean(false);
    final LazyComposableFuture<String> res2 = LazyComposableFuture.submit(executor, new Callable<String>() {
      @Override
      public String call() throws Exception {
        if (state2.compareAndSet(false, true)) {
          Thread.sleep(100);
          return "first";
        } else {
          Thread.sleep(50);
          return "second";
        }
      }
    }, false).doubleDispatch(scheduler, 150, TimeUnit.MILLISECONDS);

    final long t3 = System.currentTimeMillis();
    final String result2 = res2.get();
    final long t4 = System.currentTimeMillis();

    Assert.assertEquals(result2, "first");
    Assert.assertTrue((t4 - t3) < 150);

    final AtomicBoolean state3 = new AtomicBoolean(false);
    final LazyComposableFuture<String> res3 = LazyComposableFuture.submit(executor, new Callable<String>() {
      @Override
      public String call() throws Exception {
        if (state3.compareAndSet(false, true)) {
          Thread.sleep(100);
          return "first";
        } else {
          Thread.sleep(200);
          return "second";
        }
      }
    }, false).doubleDispatch(scheduler, 50, TimeUnit.MILLISECONDS);

    final long t5 = System.currentTimeMillis();
    final String result3 = res3.get();
    final long t6 = System.currentTimeMillis();

    Assert.assertEquals(result3, "first");
    Assert.assertTrue((t6 - t5) < 200);

    executor.shutdown();
    scheduler.shutdown();
  }

  @Test
  public void testColdStream() {
    final Scheduler scheduler = new ThreadPoolBasedScheduler(1,"test-cold-stream");
    final ComposableFuture<String> first = LazyComposableFuture.schedule(scheduler, new Callable<String>() {
      @Override
      public String call() throws Exception {
        return "first";
      }
    }, 100, TimeUnit.MILLISECONDS);

    final ComposableFuture<String> second = LazyComposableFuture.schedule(scheduler, new Callable<String>() {
      @Override
      public String call() throws Exception {
        return "second";
      }
    }, 200, TimeUnit.MILLISECONDS);

    final ComposableFuture<String> third = LazyComposableFuture.schedule(scheduler, new Callable<String>() {
      @Override
      public String call() throws Exception {
        return "third";
      }
    }, 300, TimeUnit.MILLISECONDS);

    final Observable<String> stream = ComposableFutures.toColdObservable(Arrays.asList(first, second, third));

    for (int i = 0; i < 10; i++) {
      final Iterable<String> results = stream.toBlocking().toIterable();
      final List<String> resultsList = new ArrayList<>();
      for (final String result : results) {
        resultsList.add(result);
      }

      Assert.assertEquals(resultsList.size(), 3);
      Assert.assertEquals(resultsList.get(0), "first");
      Assert.assertEquals(resultsList.get(1), "second");
      Assert.assertEquals(resultsList.get(2), "third");
    }

    scheduler.shutdown();
  }

  @Test
  public void testColdRecursiveStream() {

    final AtomicInteger counter = new AtomicInteger(0);
    final int repeats = 5;

    final ComposableFuture<String> lazyString = LazyComposableFuture.apply(new Supplier<String>() {
      @Override
      public String get() {
        counter.incrementAndGet();
        return "stateless lazy evaluated";
      }
    });

    final Observable<String> stringObservable = ComposableFutures.toColdObservable(new RecursiveFutureProvider<String>() {
      @Override
      public ComposableFuture<String> provide() {
        return lazyString;
      }

      @Override
      public Predicate<String> createStopCriteria() {
        return new Predicate<String>() {
          private volatile int i;
          @Override
          public boolean apply(final String s) {
            return ++i >= repeats;
          }
        };
      }
    });

    stringObservable.toBlocking().first();
    stringObservable.toBlocking().first();

    Assert.assertTrue("counter of evaluations should be 10", counter.get() == repeats * 2);
  }
}
