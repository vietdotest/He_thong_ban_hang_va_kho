package vn.codegym.salesinventory.dao;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import vn.codegym.salesinventory.service.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

class PurchaseHistoryAcceptanceIT extends MvpWorkflowSupport {
    long invoice(long order, String quantity) {
        long version = Sql.id(one("SELECT version FROM orders WHERE id=?", order).get("version"));
        long shipment =
                fulfillment.dispatch(
                        worker, order, version, key(), take(order, quantity), "Xuất theo từng lần");
        fulfillment.deliver(
                worker, shipment, 1, key(), "Người nhận", received(shipment, quantity), "");
        return new InvoiceService(source, clock).issue(user("ACCOUNTANT"), shipment, 2, key());
    }

    long started(String quantity) {
        long order = accepted(quantity);
        fulfillment.startPicking(worker, order, 2, key());
        return order;
    }

    @Test
    void sumsBaseQuantityAndCountsAnOrderOnceAcrossSeveralInvoices() {
        long first = started("4");
        invoice(first, "1");
        invoice(first, "3");
        long second = started("2");
        invoice(second, "2");
        var history = new PurchaseHistoryService(source, clock).load(sales, dealer);
        assertThat(history.items()).hasSize(1);
        var item = history.items().get(0);
        assertThat(item.totalQuantity()).isEqualByComparingTo("6");
        assertThat(item.purchaseCount()).isEqualTo(2);
        assertThat(item.averageQuantity()).isEqualByComparingTo("3");
        assertThat(history.lastOrderId()).isEqualTo(second);
        assertThat(history.previousItems())
                .singleElement()
                .satisfies(
                        previous -> {
                            assertThat(previous.unitId()).isEqualTo(unit);
                            assertThat(previous.quantity()).isEqualTo("2");
                            assertThat(previous.available()).isTrue();
                        });
        assertThat(history.display().toString())
                .doesNotContain("cost", "selling", "floor", "price");
    }

    @Test
    void usesVietnamCalendarWindowAndExcludesDraftUninvoicedAndFuturePurchases() {
        orders.save(
                sales,
                0,
                new OrderService.Input(
                        dealer,
                        address,
                        LocalDate.of(2027, 1, 4),
                        0,
                        key(),
                        java.util.List.of(
                                new OrderService.LineInput(product, unit, BigDecimal.ONE))));
        assertThat(new PurchaseHistoryService(source, clock).load(sales, dealer).items()).isEmpty();
        long order = started("4");
        long included = invoice(order, "1");
        long old = invoice(order, "1");
        long future = invoice(order, "1");
        update("UPDATE mvp_invoices SET invoice_date='2026-10-03' WHERE id=?", included);
        update("UPDATE mvp_invoices SET invoice_date='2026-10-02' WHERE id=?", old);
        update("UPDATE mvp_invoices SET invoice_date='2027-01-04' WHERE id=?", future);
        var history =
                new PurchaseHistoryService(
                                source,
                                Clock.fixed(Instant.parse("2027-01-02T17:00:00Z"), ZoneOffset.UTC))
                        .load(sales, dealer);
        assertThat(history.from()).isEqualTo(LocalDate.of(2026, 10, 3));
        assertThat(history.to()).isEqualTo(LocalDate.of(2027, 1, 3));
        assertThat(history.items())
                .singleElement()
                .satisfies(item -> assertThat(item.totalQuantity()).isEqualByComparingTo("1"));
        assertThat(history.previousItems().get(0).quantity()).isEqualTo("1");
    }

    @Test
    void rechecksCurrentAssignmentEvenWhenUserCanReadAllDealers() {
        var history = new PurchaseHistoryService(source, clock);
        assertThatThrownBy(() -> history.load(user("SALES"), dealer))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> history.load(manager, dealer))
                .isInstanceOf(SecurityException.class);
        long portal = user();
        update("UPDATE users SET account_kind='DEALER' WHERE id=?", portal);
        update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code='DEALER'", portal);
        assertThatThrownBy(() -> history.load(portal, dealer))
                .isInstanceOf(SecurityException.class);
        long replacement = user("SALES");
        update("UPDATE dealers SET primary_staff_id=? WHERE id=?", replacement, dealer);
        assertThatThrownBy(() -> history.load(sales, dealer)).isInstanceOf(SecurityException.class);
        assertThat(history.load(replacement, dealer).items()).isEmpty();
        update("UPDATE users SET status='ADMIN_LOCKED' WHERE id=?", replacement);
        assertThatThrownBy(() -> history.load(replacement, dealer))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void historicalItemsRemainVisibleButInactiveProductsCannotBeAdded() {
        long order = started("1");
        invoice(order, "1");
        update("UPDATE products SET status='DISCONTINUED' WHERE id=?", product);
        var history = new PurchaseHistoryService(source, clock).load(sales, dealer);
        assertThat(history.items()).hasSize(1);
        assertThat(history.previousItems().get(0).available()).isFalse();
        assertThat(history.previousItems().get(0).notice()).isNotBlank();
    }

    @Test
    void quantitiesUseTheInvoiceBaseUnitAndUnrepresentableSuggestionFallsBackToOne() {
        long order = started("1");
        long id = invoice(order, "1");
        update("UPDATE mvp_invoice_lines SET quantity=0.123456789012 WHERE invoice_id=?", id);
        var history = new PurchaseHistoryService(source, clock).load(sales, dealer);
        assertThat(history.items().get(0).totalQuantity()).isEqualByComparingTo("0.123456789012");
        assertThat(history.previousItems().get(0).quantity()).isEqualTo("1");
        assertThat(history.previousItems().get(0).notice()).contains("kiểm tra lại");
    }
}
