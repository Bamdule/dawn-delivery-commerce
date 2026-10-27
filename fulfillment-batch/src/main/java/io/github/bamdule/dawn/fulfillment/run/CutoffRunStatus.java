package io.github.bamdule.dawn.fulfillment.run;

// 스텝이 끝날 때마다 한 칸씩 오른다. 재시작한 스텝은 이 값을 보고 이미 끝난 일을 건너뛴다 (docs/design/05)
public enum CutoffRunStatus {
    STARTED,
    TARGET_FIXED,
    ALLOCATED,
    UNFILLABLE_PUBLISHED,
    SHIPMENT_CREATED,
    VERIFIED,
    VERIFY_FAILED;

    public boolean reached(CutoffRunStatus other) {
        return ordinal() >= other.ordinal();
    }

    public boolean finished() {
        return this == VERIFIED || this == VERIFY_FAILED;
    }
}
