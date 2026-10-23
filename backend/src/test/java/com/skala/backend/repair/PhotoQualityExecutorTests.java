package com.skala.backend.repair;

import com.skala.backend.repair.quality.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.io.InputStream;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class PhotoQualityExecutorTests {
    @Test void concurrentValidationCannotExceedWorkerAndQueueBounds() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        PhotoQualityChecker blocked=new PhotoQualityChecker() {
            public void validate(InputStream in) {entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
            public Result check(InputStream in) {throw new AssertionError("unused");}
        };
        var config=new PhotoQualityProperties(true,"bounded",640,480,10000,10000,25000000,1024,40,245,.6,.4,100,1,1,3,120);
        var executor=new PhotoQualityExecutor(config,blocked);var file=new MockMultipartFile("file","x.png","image/png",QualityImages.png(1,1));
        try(var callers=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=callers.submit(()->executor.validate(file));assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            var second=callers.submit(()->executor.validate(file));await().atMost(java.time.Duration.ofSeconds(3)).until(()->executor.pool.getQueue().size()==1);
            assertThatThrownBy(()->executor.validate(file)).isInstanceOf(ApiError.class).hasMessage("PHOTO_VALIDATION_BUSY");
            assertThat(executor.pool.getActiveCount()).isEqualTo(1);assertThat(executor.pool.getQueue()).hasSize(1);release.countDown();first.get(5,TimeUnit.SECONDS);second.get(5,TimeUnit.SECONDS);
        } finally {release.countDown();executor.close();}
    }
}
