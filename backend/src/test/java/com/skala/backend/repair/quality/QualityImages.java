package com.skala.backend.repair.quality;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Random;

public final class QualityImages {
    public static BufferedImage texture(int width,int height) {
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);var random=new Random(42);
        for(int y=0;y<height;y++) for(int x=0;x<width;x++){int n=70+random.nextInt(140);image.setRGB(x,y,(n<<16)|(n<<8)|n);}return image;
    }
    public static byte[] bytes(BufferedImage image,String format) {
        try(var out=new ByteArrayOutputStream()){if(!ImageIO.write(image,format,out)) throw new IllegalStateException("Image writer missing");return out.toByteArray();}catch(IOException e){throw new IllegalStateException(e);}
    }
    public static byte[] png(int width,int height) {return bytes(texture(width,height),"png");}
    public static PhotoQualityProperties policy() {return new PhotoQualityProperties(true,"test-v1",640,480,10000,10000,25000000,1024,40,245,.60,.40,100,2,8,3,120);}
}
