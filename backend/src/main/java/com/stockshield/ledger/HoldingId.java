package com.stockshield.ledger;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class HoldingId implements Serializable {

    private String acntNo;
    private String tckCd;
}
