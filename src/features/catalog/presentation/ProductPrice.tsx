import type { StoreProduct } from "@/features/catalog/model";
import { formatArs } from "@/shared/format/ars";

type ProductPriceProps = {
  product: Pick<StoreProduct, "price" | "compareAtPrice">;
  className: string;
  priceClassName: string;
  compareClassName: string;
};

export function ProductPrice({
  product,
  className,
  priceClassName,
  compareClassName,
}: ProductPriceProps) {
  return (
    <div className={className}>
      <strong className={priceClassName}>{formatArs(product.price)}</strong>
      {product.compareAtPrice && (
        <span className={compareClassName}>Antes {formatArs(product.compareAtPrice)}</span>
      )}
    </div>
  );
}
