package com.skala.backend.repair.quality;

import com.skala.backend.repair.ApiError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.awt.*;
import java.awt.image.*;
import java.io.*;
import static org.assertj.core.api.Assertions.*;

class JavaPhotoQualityCheckerTests {
    final JavaPhotoQualityChecker checker=new JavaPhotoQualityChecker(QualityImages.policy());
    PhotoQualityChecker.Result check(BufferedImage image) throws Exception {return checker.check(new ByteArrayInputStream(QualityImages.bytes(image,"png")));}
    @ParameterizedTest @ValueSource(strings={"png","jpeg"}) void realJpegAndPngDecode(String format) throws Exception {
        byte[] bytes=QualityImages.bytes(QualityImages.texture(640,480),format);
        var result=checker.check(new ByteArrayInputStream(bytes));assertThat(result.issues()).isEmpty();assertThat(result.sharpness()).isGreaterThan(100);assertThat(result.originalWidth()).isEqualTo(640);
    }
    @Test void blackWhiteAndMultipleWarningsAreWarningsNotDecodeErrors() throws Exception {
        var black=new BufferedImage(32,32,BufferedImage.TYPE_INT_RGB);var dark=check(black);
        assertThat(dark.darkRatio()).isEqualTo(1);assertThat(dark.meanBrightness()).isZero();assertThat(dark.sharpness()).isZero();assertThat(dark.issues()).containsExactly("LOW_RESOLUTION","TOO_DARK","BLURRY");
        var g=black.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,32,32);g.dispose();var white=check(black);assertThat(white.brightRatio()).isEqualTo(1);assertThat(white.meanBrightness()).isCloseTo(255,within(1e-9));assertThat(white.issues()).contains("TOO_BRIGHT","BLURRY");
    }
    @Test void increasingBlurReducesVarianceForSameSyntheticSource() throws Exception {
        var source=QualityImages.texture(256,256);double initial=check(source).sharpness();
        float[] weights=new float[9];java.util.Arrays.fill(weights,1f/9f);var filter=new ConvolveOp(new Kernel(3,3,weights),ConvolveOp.EDGE_NO_OP,null);
        var once=filter.filter(source,null);var twice=filter.filter(once,null);
        assertThat(check(once).sharpness()).isLessThan(initial);assertThat(check(twice).sharpness()).isLessThan(check(once).sharpness());
    }
    @Test void tinyTransparentAndPortraitImagesAreDeterministic() throws Exception {
        var tiny=new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB);var r=check(tiny);
        assertThat(r.sharpness()).isZero();assertThat(r.meanBrightness()).isCloseTo(255,within(1e-9));assertThat(r.issues()).contains("LOW_RESOLUTION","TOO_BRIGHT","BLURRY");
        var portrait=check(QualityImages.texture(480,640));assertThat(portrait.issues()).doesNotContain("LOW_RESOLUTION");assertThat(portrait.inspectionWidth()).isEqualTo(480);
    }
    @Test void resizingPreservesAspectRatioWithoutChangingOriginal() throws Exception {
        var image=QualityImages.texture(2000,1000);int pixel=image.getRGB(5,5);var r=check(image);
        assertThat(r.originalWidth()).isEqualTo(2000);assertThat(r.inspectionWidth()).isEqualTo(1024);assertThat(r.inspectionHeight()).isEqualTo(512);assertThat(image.getRGB(5,5)).isEqualTo(pixel);
    }
    @Test void corruptUnsupportedAndDimensionsRejectedBeforePixelRead() {
        assertThatThrownBy(()->checker.validate(new ByteArrayInputStream(new byte[]{(byte)137,80,78,71,13,10,26,10}))).isInstanceOfAny(ApiError.class,IOException.class);
        byte[] jpeg=QualityImages.bytes(QualityImages.texture(100,100),"jpeg");assertThatThrownBy(()->checker.validate(new ByteArrayInputStream(java.util.Arrays.copyOf(jpeg,jpeg.length/2)))).isInstanceOfAny(ApiError.class,IOException.class);
        byte[] gif=QualityImages.bytes(QualityImages.texture(10,10),"gif");assertThatThrownBy(()->checker.validate(new ByteArrayInputStream(gif))).isInstanceOf(ApiError.class);
        var p=new PhotoQualityProperties(true,"small",1,1,100,100,1000,64,40,245,.6,.4,100,1,1,3,120);
        assertThatThrownBy(()->new JavaPhotoQualityChecker(p).validate(new ByteArrayInputStream(QualityImages.png(100,100)))).isInstanceOf(ApiError.class).hasMessage("IMAGE_DIMENSIONS_LIMIT");
    }
    @Test void thresholdChangesChangePolicyIdentity() {
        var p=QualityImages.policy();var changed=new PhotoQualityProperties(true,p.policyVersion(),640,480,10000,10000,25000000,1024,40,245,.60,.40,101,2,8,3,120);
        assertThat(p.effectiveVersion()).isNotEqualTo(changed.effectiveVersion());
    }
}
