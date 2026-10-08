package vn.codegym.salesinventory.service;
import java.math.*;
import java.util.*;
import vn.codegym.salesinventory.validation.CatalogValidation;
/** Pricing rules are shared by quote, confirmation and tests, never trusted from a browser. */
public final class DiscountMath {
    private DiscountMath(){}
    public record Tier(long policyId,long revision,long tierId,String name,String mode,BigDecimal minimum,BigDecimal value){}
    public record Selection(Tier tier,BigDecimal totalDiscount){public boolean applied(){return tier!=null;}}
    public static BigDecimal money(BigDecimal value){if(value==null||value.signum()<0||value.scale()>128||value.precision()>128)throw new IllegalArgumentException("Số tiền không hợp lệ.");var rounded=value.setScale(4,RoundingMode.HALF_UP);if(rounded.precision()>19)throw new IllegalArgumentException("Số tiền vượt giới hạn dữ liệu.");return rounded;}
    public static BigDecimal lineGross(BigDecimal quantity,BigDecimal selling){if(quantity==null||quantity.signum()<=0)throw new IllegalArgumentException("Số lượng phải lớn hơn 0.");return money(quantity.multiply(CatalogValidation.decimal(selling,4,false)));}
    public static BigDecimal lineDiscount(BigDecimal quantity,BigDecimal selling,Tier tier){if(tier==null)return BigDecimal.ZERO.setScale(4);BigDecimal value=tier.value;
        if("PERCENT".equals(tier.mode)){if(value.signum()<0||value.compareTo(new BigDecimal("100"))>0)throw new IllegalArgumentException("Chiết khấu phần trăm trong khoảng 0–100.");return money(lineGross(quantity,selling).multiply(value).divide(new BigDecimal("100")));}
        if(!"FIXED".equals(tier.mode))throw new IllegalArgumentException("Loại chiết khấu không hợp lệ.");CatalogValidation.decimal(value,4,false);if(value.compareTo(selling)>0)throw new IllegalArgumentException("Chiết khấu tiền cố định vượt giá bán áp dụng.");return money(quantity.multiply(value));
    }
    /** Caller supplies all lines of ONE SKU, including lines in different units. */
    public static Selection best(List<BigDecimal> baseQuantities,BigDecimal selling,List<Tier> candidates){if(baseQuantities.isEmpty())throw new IllegalArgumentException("Thiếu dòng hàng.");for(var line:baseQuantities)if(line==null||line.signum()<=0)throw new IllegalArgumentException("Số lượng cơ sở phải lớn hơn 0.");CatalogValidation.decimal(selling,4,false);BigDecimal quantity=baseQuantities.stream().reduce(BigDecimal.ZERO,BigDecimal::add);var perPolicy=new TreeMap<Long,Tier>();
        for(var tier:candidates)if(tier.minimum.compareTo(quantity)<=0){var old=perPolicy.get(tier.policyId);if(old==null || tier.minimum.compareTo(old.minimum)>0 || (tier.minimum.compareTo(old.minimum)==0 && tier.tierId<old.tierId))perPolicy.put(tier.policyId,tier);}
        Tier winner=null;BigDecimal best=BigDecimal.ZERO.setScale(4);for(var tier:perPolicy.values()){BigDecimal amount=BigDecimal.ZERO.setScale(4);for(var line:baseQuantities)amount=money(amount.add(lineDiscount(line,selling,tier)));if(winner==null||amount.compareTo(best)>0||(amount.compareTo(best)==0&&tier.policyId<winner.policyId)){winner=tier;best=amount;}}
        return new Selection(winner,best);
    }
}
