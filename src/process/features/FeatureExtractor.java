package process.features;

import utils.yield.Yielderable;

import java.util.ArrayList;
import java.util.stream.Collectors;

import static process.IntegralImage.rectangleSum;


public class FeatureExtractor {
    public static final int typeA = 1;
    public static final int widthTypeA = 2;
    public static final int heightTypeA = 1;
    public static int computeTypeA(int[][] integralImage, Rectangle r) {
        /**
         * a ------- b ------- c
         * -         -         -
         * -   R1    -    R2   -
         * -         -         -
         * d ------- e ------- f
         */

        int w = r.getWidth() / widthTypeA;
        int h = r.getHeight();
        int r1 = rectangleSum(integralImage, r.getX(), r.getY(), w, h);
        int r2 = rectangleSum(integralImage, r.getX() + w, r.getY(), w, h);

        return r1 - r2;
    }

    public ArrayList<Feature> getAllTypeA(int[][] integralImage, int frameWidth, int frameHeight) {
        return listFeaturePositions(widthTypeA, heightTypeA, frameWidth, frameHeight)
                .stream()
                .map(r -> new Feature(r, typeA, integralImage, 0, 1))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    public static final int typeB = 2;
    public static final int widthTypeB = 3;
    public static final int heightTypeB = 1;
    public static int computeTypeB(int[][] integralImage, Rectangle r) {
        /**
         * a ------- b ------- c ------- d
         * -         -         -         -
         * -   R1    -    R2   -    R3   -
         * -         -         -         -
         * e ------- f ------- g ------- h
         */

        int w = r.getWidth() / widthTypeA;
        int h = r.getHeight();
        int r1 = rectangleSum(integralImage, r.getX(), r.getY(), w, h);
        int r2 = rectangleSum(integralImage, r.getX() + w, r.getY(), w, h);
        int r3 = rectangleSum(integralImage, r.getX() + w + w, r.getY(), w, h);

        return r1 - r2 + r3;
    }

    public ArrayList<Feature> getAllTypeB(int[][] integralImage, int frameWidth, int frameHeight) {
        return listFeaturePositions(widthTypeB, heightTypeB, frameWidth, frameHeight)
                .stream()
                .map(r -> new Feature(r, typeB, integralImage, 0, 1))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    public static final int typeC = 3;
    public static final int widthTypeC = 1;
    public static final int heightTypeC = 2;
    public static int computeTypeC(int[][] integralImage, Rectangle r) {
        /**
         * a ------- b
         * -         -
         * -   R1    -
         * -         -
         * c ------- d
         * -         -
         * -   R2    -
         * -         -
         * e ------- f
         */

        int w = r.getWidth();
        int h = r.getHeight() / heightTypeC;
        int r1 = rectangleSum(integralImage, r.getX(), r.getY(), w, h);
        int r2 = rectangleSum(integralImage, r.getX(), r.getY() + h, w, h);

        return r2 - r1;
    }

    public ArrayList<Feature> getAllTypeC(int[][] integralImage, int frameWidth, int frameHeight) {
        return listFeaturePositions(widthTypeC, heightTypeC, frameWidth, frameHeight)
                .stream()
                .map(r -> new Feature(r, typeC, integralImage, 0, 1))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    public static final int typeD = 4;
    public static final int widthTypeD = 1;
    public static final int heightTypeD = 3;
    public static int computeTypeD(int[][] integralImage, Rectangle r) {
        /**
         * a ------- b
         * -         -
         * -   R1    -
         * -         -
         * c ------- d
         * -         -
         * -   R2    -
         * -         -
         * e ------- f
         * -         -
         * -   R3    -
         * -         -
         * g ------- h
         */

        int w = r.getWidth();
        int h = r.getHeight() / heightTypeD;
        int r1 = rectangleSum(integralImage, r.getX(), r.getY(), w, h);
        int r2 = rectangleSum(integralImage, r.getX(), r.getY() + h, w, h);
        int r3 = rectangleSum(integralImage, r.getX(), r.getY() + h + h, w, h);

        return r1 - r2 + r3;
    }

    public ArrayList<Feature> getAllTypeD(int[][] integralImage, int frameWidth, int frameHeight) {
        return listFeaturePositions(widthTypeD, heightTypeD, frameWidth, frameHeight)
                .stream()
                .map(r -> new Feature(r, typeD, integralImage, 0, 1))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    public static final int typeE = 5;
    public static final int widthTypeE = 2;
    public static final int heightTypeE = 2;
    public static int computeTypeE(int[][] integralImage, Rectangle r) {
        /**
         * a ------- b ------- c
         * -         -         -
         * -   R1    -    R2   -
         * -         -         -
         * d ------- e ------- f
         * -         -         -
         * -   R3    -    R4   -
         * -         -         -
         * g ------- h ------- i
         */

        int w = r.getWidth() / widthTypeE;
        int h = r.getHeight() / heightTypeE;
        int r1 = rectangleSum(integralImage, r.getX(), r.getY(), w, h);
        int r2 = rectangleSum(integralImage, r.getX() + w, r.getY(), w, h);
        int r3 = rectangleSum(integralImage, r.getX(), r.getY() + h, w, h);
        int r4 = rectangleSum(integralImage, r.getX() + w, r.getY() + h, w, h);

        return r1 - r2 - r3 + r4;
    }

    public ArrayList<Feature> getAllTypeE(int[][] integralImage, int frameWidth, int frameHeight) {
        return listFeaturePositions(widthTypeE, heightTypeE, frameWidth, frameHeight)
                .stream()
                .map(r -> new Feature(r, typeE, integralImage, 0, 1))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    public Yielderable<Rectangle> streamFeaturePositions(int sizeX, int sizeY, int frameWidth, int frameHeight) {
        return yield -> {
            for (int w = sizeX; w <= frameWidth; w += sizeX) {
                for (int h = sizeY; h <= frameHeight; h += sizeY) {
                    for (int x = 0; x <= frameWidth - w; x++) {
                        for (int y = 0; y <= frameHeight - h; y++) {
                            yield.returning(new Rectangle(x, y, w, h));
                        }
                    }
                }
            }
        };
    }

    private ArrayList<Rectangle> listFeaturePositions(int sizeX, int sizeY, int frameWidth, int frameHeight) {
        ArrayList<Rectangle> rectangles = new ArrayList<>();
        for (Rectangle r : streamFeaturePositions(sizeX, sizeY, frameWidth, frameHeight))
            rectangles.add(r);
        return rectangles;
    }
}