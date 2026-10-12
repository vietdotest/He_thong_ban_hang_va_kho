package vn.codegym.salesinventory.service;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class DiscountMathTest {
    BigDecimal n(String n){return new BigDecimal(n);}
    DiscountMath.Tier tier(long policy,long tier,String threshold,String mode,String value){return new DiscountMath.Tier(policy,1,tier,"Chính sách "+policy,mode,n(threshold),n(value));}
    @Test void sameSkuTotalsAcrossBaseBoxAndPackageLinesChooseHighestEligibleTier(){var best=DiscountMath.best(List.of(n("12"),n("24"),n("6")),n("100"),List.of(tier(1,1,"20","PERCENT","5"),tier(1,2,"40","PERCENT","10")));assertThat(best.tier().tierId()).isEqualTo(2);assertThat(best.totalDiscount()).isEqualByComparingTo("420.0000");}
    @Test void bestBenefitDoesNotStackAndStablePolicyIdBreaksTies(){var candidates=List.of(tier(9,9,"1","FIXED","12"),tier(2,2,"1","PERCENT","12"),tier(1,1,"1","PERCENT","10"));var best=DiscountMath.best(List.of(n("10")),n("100"),candidates);assertThat(best.tier().policyId()).isEqualTo(2);assertThat(best.totalDiscount()).isEqualByComparingTo("120");}
    @Test void roundsMoneyPerLineThenSumsRatherThanMultiplyingRoundedPerBaseDiscount(){var tier=tier(1,1,"0","PERCENT","3");assertThat(DiscountMath.lineDiscount(n("100000"),n("100.0001"),tier)).isEqualByComparingTo("300000.3000");assertThat(DiscountMath.money(n("1.23445"))).isEqualByComparingTo("1.2345");assertThat(DiscountMath.best(List.of(n("0.333333"),n("0.333333")),n("1.0001"),List.of(tier)).totalDiscount()).isEqualByComparingTo("0.0200");}
    @Test void fixedDiscountAndPercentageAreBoundedAndZeroQuantityIsRejected(){assertThatThrownBy(()->DiscountMath.lineDiscount(n("1"),n("10"),tier(1,1,"0","FIXED","11"))).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->DiscountMath.lineDiscount(n("1"),n("10"),tier(1,1,"0","PERCENT","100.000001"))).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->DiscountMath.best(List.of(n("0")),n("10"),List.of())).isInstanceOf(IllegalArgumentException.class);assertThat(DiscountMath.lineDiscount(n("1"),n("10"),tier(1,1,"0","PERCENT","100"))).isEqualByComparingTo("10");}
    @Test void thresholdJustBelowAndAtBoundary(){var policy=tier(1,1,"24","PERCENT","10");assertThat(DiscountMath.best(List.of(n("23.999999")),n("100"),List.of(policy)).applied()).isFalse();assertThat(DiscountMath.best(List.of(n("24")),n("100"),List.of(policy)).applied()).isTrue();}
}
