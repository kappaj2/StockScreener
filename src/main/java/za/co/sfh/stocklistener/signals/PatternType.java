package za.co.sfh.stocklistener.signals;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PatternType {
    BREAKOUT("B"),
    UNSHARPEN_MASK("UM"),
    STRONG_CLIMB("SC"),
    INVERTED_VEE("IV"),
    MOMENTUM_VERY_HIGH("MVH"),
    MOMENTUM_HIGH("MH"),
    HIGH_WATCH("HW"),
    HIGH_TIGHT_FLAG("HTF"),
    GAP_AND_RUN("GAR"),
    GRINDING_MOMENTUM("GM"),
    ALPHA_PATTERN("AP");

    private final String code;
}