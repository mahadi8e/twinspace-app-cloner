package top.niunaijun.blackboxa.util;

import android.graphics.Point;

public class MathUtil {

    public MathUtil() {
    }

    
    public static int getDistance(float x1, float y1, float x2, float y2) {
        return (int) Math.sqrt(Math.pow(x1 - x2, 2) + Math.pow(y1 - y2, 2));
    }

    
    public static Point getPointByCutLength(Point A, Point B, int cutLength) {
        float radian = getRadian(A, B);
        return new Point(A.x + (int) (cutLength * Math.cos(radian)), A.y + (int) (cutLength * Math.sin(radian)));
    }

    
    public static float getRadian(Point A, Point B) {
        float lenA = B.x - A.x;
        float lenB = B.y - A.y;
        float lenC = (float) Math.sqrt(lenA * lenA + lenB * lenB);
        float radian = (float) Math.acos(lenA / lenC);
        radian = radian * (B.y < A.y ? -1 : 1);
        return radian;
    }
}