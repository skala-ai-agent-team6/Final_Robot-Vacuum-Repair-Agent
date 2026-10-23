package com.skala.backend.repair.quality;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.util.Arrays;

class PhotoQualityBenchmarkTests {
    @Test void recordSyntheticImageProcessingTimes() throws Exception {
        var checker=new JavaPhotoQualityChecker(QualityImages.policy());
        for(int[] size:new int[][]{{640,480},{1920,1080},{4000,3000}}) {
            var image=QualityImages.texture(size[0],size[1]);byte[] bytes=QualityImages.bytes(image,"jpeg");image.flush();
            for(int i=0;i<2;i++) checker.check(new ByteArrayInputStream(bytes));
            double[] milliseconds=new double[5];for(int i=0;i<5;i++){long start=System.nanoTime();checker.check(new ByteArrayInputStream(bytes));milliseconds[i]=(System.nanoTime()-start)/1_000_000.0;}
            Arrays.sort(milliseconds);
            System.out.printf(java.util.Locale.ROOT,"PHOTO_BENCH width=%d height=%d jpegBytes=%d runs=5 medianMs=%.3f maxMs=%.3f processors=%d java=%s os=%s arch=%s%n",size[0],size[1],bytes.length,milliseconds[2],milliseconds[4],Runtime.getRuntime().availableProcessors(),System.getProperty("java.version"),System.getProperty("os.name"),System.getProperty("os.arch"));
        }
    }
}
