package za.co.sfh.stocklistener.journal;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CycleContext {
    TRENDING("Trending"),
    CHOPPY("Choppy"),
    NEWSY("Newsy"),
    RANGE_BOUND("Range Bound"),
    GAP_DAY("Gap Day");

    private final String label;
}