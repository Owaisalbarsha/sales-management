package com.salesmanagement.vanops.internal.entity;

import com.salesmanagement.shared.domain.BaseEntity;
import com.salesmanagement.vanops.internal.enums.ReturnSheetStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The end-of-day return document — what a representative brought back from the field, line
 * by line. When the warehouse manager completes it, the stock physically moves van →
 * warehouse via {@code InventoryFacade.returnVanToWarehouse}.
 *
 * <p>Owned by the {@code vanops} module. Same modelling rules as {@link DemandOrder}:
 * cross-module {@code representativeId} is a plain {@code Long}, within-module lines are a
 * cascaded {@code @OneToMany}.</p>
 *
 * <p><strong>Reconciliation note:</strong> by the workflow decision we kept this simple —
 * a single quantity per line, no outcome enum. Whatever isn't returned is implicitly
 * "consumed" (sold via invoices during the day). A future "damaged/expired" breakdown can
 * be added as another column on the line without touching this header.</p>
 */
@Entity
@Table(name = "return_sheets")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReturnSheet extends BaseEntity {

    /** Cross-module FK to {@code users(id)} — must reference a {@code SALES_REP}. */
    @Column(name = "representative_id", nullable = false)
    private Long representativeId;

    @Column(name = "return_date", nullable = false)
    private LocalDate returnDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReturnSheetStatus status;

    @OneToMany(mappedBy = "returnSheet", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ReturnSheetLine> lines = new ArrayList<>();

    public ReturnSheet(Long representativeId, LocalDate returnDate) {
        this.representativeId = representativeId;
        this.returnDate = returnDate;
        this.status = ReturnSheetStatus.DRAFT;
    }

    public void addLine(ReturnSheetLine line) {
        line.setReturnSheet(this);
        this.lines.add(line);
    }
}