package za.co.sfh.stocklistener.persistence.entities;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MinuteBarId implements Serializable {
    private String symbol;
    private Instant barStart;
}
