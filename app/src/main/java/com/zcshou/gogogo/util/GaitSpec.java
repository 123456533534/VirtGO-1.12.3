package com.zcshou.gogogo.util;

/**
 * 步态参数：把「步频 + 步幅」换算成移动所需的速度与按步推进节拍。
 *
 * <p>仅用于走路/跑步两档；骑行档不走步态，仍用原来的连续速度。</p>
 *
 * <ul>
 *     <li>等效速度 m/s = 步频(步/分) × 步幅(米) ÷ 60</li>
 *     <li>步间隔 ms = 60000 ÷ 步频(步/分)，即每一步之间位置保持不动的时长</li>
 *     <li>每步距离 m = 步幅(米)，摇杆与回放都按「一步一停」推进</li>
 * </ul>
 *
 * <p>非法参数（≤0、NaN、Inf、解析失败）逐项回退到默认值，保证任何时候都能得到一个可用的步态。</p>
 */
public final class GaitSpec {

    public static final int DEFAULT_WALK_CADENCE = 100;   // 步/分
    public static final double DEFAULT_WALK_STRIDE = 0.72D; // 米/步 → 1.2 m/s
    public static final int DEFAULT_RUN_CADENCE = 180;    // 步/分
    public static final double DEFAULT_RUN_STRIDE = 1.2D;  // 米/步 → 3.6 m/s

    private final int cadencePerMin;
    private final double strideMeters;

    private GaitSpec(int cadencePerMin, double strideMeters) {
        this.cadencePerMin = cadencePerMin;
        this.strideMeters = strideMeters;
    }

    /** 用显式步频/步幅构造，非法值回退到步行默认。 */
    public static GaitSpec of(int cadencePerMin, double strideMeters) {
        int cadence = cadencePerMin > 0 ? cadencePerMin : DEFAULT_WALK_CADENCE;
        double stride = (strideMeters > 0.0D && !Double.isNaN(strideMeters)
                && !Double.isInfinite(strideMeters)) ? strideMeters : DEFAULT_WALK_STRIDE;
        return new GaitSpec(cadence, stride);
    }

    /** 从字符串解析（设置页存的是字符串），解析失败回退到传入的默认步频/步幅。 */
    public static GaitSpec fromStrings(String cadenceRaw, String strideRaw,
                                       int defaultCadence, double defaultStride) {
        int cadence = parseIntSafe(cadenceRaw, defaultCadence);
        double stride = parseDoubleSafe(strideRaw, defaultStride);
        return of(cadence, stride);
    }

    /** 步行默认步态。 */
    public static GaitSpec walkDefault() {
        return new GaitSpec(DEFAULT_WALK_CADENCE, DEFAULT_WALK_STRIDE);
    }

    /** 跑步默认步态。 */
    public static GaitSpec runDefault() {
        return new GaitSpec(DEFAULT_RUN_CADENCE, DEFAULT_RUN_STRIDE);
    }

    private static int parseIntSafe(String raw, int fallback) {
        try {
            if (raw != null) {
                int value = Integer.parseInt(raw.trim());
                if (value > 0) {
                    return value;
                }
            }
        } catch (Exception ignored) {
            // 解析失败使用默认值
        }
        return fallback;
    }

    private static double parseDoubleSafe(String raw, double fallback) {
        try {
            if (raw != null) {
                double value = Double.parseDouble(raw.trim());
                if (value > 0.0D && !Double.isNaN(value) && !Double.isInfinite(value)) {
                    return value;
                }
            }
        } catch (Exception ignored) {
            // 解析失败使用默认值
        }
        return fallback;
    }

    /** @return 步频（步/分） */
    public int getCadencePerMin() {
        return cadencePerMin;
    }

    /** @return 步幅（米/步） */
    public double getStrideMeters() {
        return strideMeters;
    }

    /** @return 等效连续速度（米/秒），供定位 setSpeed 与对外速度展示使用 */
    public double getSpeedMps() {
        return cadencePerMin * strideMeters / 60.0D;
    }

    /** @return 每步推进距离（米），等于步幅 */
    public double getStepMeters() {
        return strideMeters;
    }

    /** @return 相邻两步之间的间隔（毫秒）：60000 ÷ 步频 */
    public long getStepIntervalMs() {
        return Math.round(60000.0D / cadencePerMin);
    }
}
