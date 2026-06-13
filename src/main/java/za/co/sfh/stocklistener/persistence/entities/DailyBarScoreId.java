package za.co.sfh.stocklistener.persistence.entities;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DailyBarScoreId implements Serializable {
    private String symbol;
    private LocalDate tradeDate;
}