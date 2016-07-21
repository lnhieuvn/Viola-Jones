package GUI;

import process.Converters;
import process.Filters;
import process.IntegralImage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.UUID;

public class ImageHandler {

    private BufferedImage bufferedImage;
    private int width;
    private int height;
    private int[][] crGrayImage;// Centered & reduced gray image
    private int[][] integralImage;
    private final UUID uid = UUID.randomUUID();
    private final String filePath;

    private void init() {
        this.crGrayImage = Filters.crGrayscale(this.bufferedImage);
        this.integralImage = IntegralImage.summedAreaTable(this.crGrayImage, this.width, this.height);
    }

    public ImageHandler(BufferedImage bufferedImage) {
        this.bufferedImage = bufferedImage;
        this.width = bufferedImage.getWidth();
        this.height = bufferedImage.getHeight();

        this.filePath = null;

        this.init();
    }

    public ImageHandler(String filePath) {
        BufferedImage bufferedImage = null;
        try {
            bufferedImage = ImageIO.read(new File(filePath));
        } catch (IOException e) {
            e.printStackTrace();
        }

        assert bufferedImage != null;

        this.bufferedImage = bufferedImage;
        this.width = bufferedImage.getWidth();
        this.height = bufferedImage.getHeight();

        this.filePath = filePath;

        this.init();
    }

    public ImageHandler(int[][] grayImage, int width, int height) {
        this.width = width;
        this.height = height;

        this.filePath = null;

        this.crGrayImage = new int[width][height];

        for (int x = 0; x < width; x++)
            System.arraycopy(grayImage[x], 0, this.crGrayImage[x], 0, height);

        this.integralImage = IntegralImage.summedAreaTable(this.crGrayImage, this.width, this.height);
        this.bufferedImage = Converters.intArrayToBufferedImage(this.crGrayImage, this.width, this.height);
    }

    public BufferedImage getBufferedImage() {
        return this.bufferedImage;
    }

    public int[][] getGrayImage() {
        return this.crGrayImage;
    }

    public BufferedImage getGrayBufferedImage() {
        return Converters.intArrayToBufferedImage(this.crGrayImage, this.width, this.height);
    }

    public int[][] getIntegralImage() {
        return this.integralImage;
    }

    public int getWidth() {
        return this.width;
    }

    public int getHeight() {
        return this.height;
    }

    public String getFilePath() {
        return filePath;
    }

    public UUID getUid() {
        return uid;
    }
}
