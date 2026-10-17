import Link from "next/link";
import { notFound } from "next/navigation";
import { Suspense } from "react";
import { getProduct } from "../server/catalog-api";
import { ProductAvailability } from "./ProductAvailability";
import { ProductHeader } from "./ProductHeader";
import { ProductPrice } from "./ProductPrice";
import { AddToCartButton } from "./AddToCartButton";
import { toCartItem } from "./cart-item";
import { previewCartItem, previewProduct } from "./preview-product";
import styles from "./catalog.module.css";

async function ProductContent({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  if (slug.length > 160 || !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(slug)) notFound();

  const result = await getProduct(slug);
  if (result.status === "not_found") notFound();
  if (result.status === "not_configured" || result.status === "unavailable") {
    if (result.status === "not_configured" && slug === previewProduct.slug) {
      return (
        <article className={styles.detail}>
          <p className={styles.kicker}>FICHA PRELIMINAR · DATOS EN VALIDACIÓN</p>
          <h1>{previewProduct.name}</h1>
          <p className={styles.description}>{previewProduct.detail}</p>
          <section className={styles.previewSection} aria-labelledby="preview-details-title">
            <h2 id="preview-details-title">Información del producto</h2>
            <p>La presentación y la información final deben confirmarse con el rótulo y el registro correspondientes.</p>
          </section>
          <section className={styles.previewSection} aria-labelledby="preview-price-title">
            <h2 id="preview-price-title">Precio y disponibilidad</h2>
            <p>A confirmar.</p>
            <AddToCartButton
              item={previewCartItem}
              label="Probar en el carrito"
            />
          </section>
          <p className={styles.note}>Esta demostración no confirma una compra, no reserva productos y no genera pedidos.</p>
        </article>
      );
    }
    return (
      <div className={styles.state} role="alert">
        <p>{result.status === "not_configured"
          ? "El catálogo comercial todavía no está conectado."
          : "La ficha no está disponible en este momento. Volvé a intentarlo más tarde."}</p>
        <Link className={styles.paginationLink} href="/productos">Volver al catálogo</Link>
      </div>
    );
  }
  const product = result.data;

  return (
    <article className={styles.detail}>
      <p className={styles.kicker}>
        {product.kind === "BASE" ? "PRODUCTO BASE" : `COMBO DE ${product.comboUnits} UNIDADES`}
      </p>
      <h1>{product.name}</h1>
      {product.presentation && <p className={styles.description}>{product.presentation}</p>}
      {product.ingredients && (
        <section className={styles.ingredients} aria-labelledby="ingredients-title">
          <h2 id="ingredients-title">Ingredientes informados</h2>
          <p className={styles.prose}>{product.ingredients}</p>
          <p>Verificá cantidades, modo de uso y advertencias en el rótulo vigente.</p>
        </section>
      )}
      {product.usage && (
        <section className={styles.ingredients} aria-labelledby="usage-title">
          <h2 id="usage-title">Modo de uso informado</h2>
          <p className={styles.prose}>{product.usage}</p>
        </section>
      )}
      <section className={styles.variants} aria-labelledby="price-title">
        <h2 id="price-title">Precio y disponibilidad</h2>
        <ProductPrice
          product={product}
          className={styles.prices}
          priceClassName={styles.price}
          compareClassName={styles.compare}
        />
        <ProductAvailability
          as="p"
          availability={product.availability}
          availableClassName={styles.available}
          unavailableClassName={styles.unavailable}
        />
        <AddToCartButton
          item={toCartItem(product)}
          disabled={product.availability === "OUT_OF_STOCK" || product.maxQuantity === 0}
          label="Agregar al carrito"
        />
      </section>
      <p className={styles.note}>La compra online aún no está habilitada.</p>
    </article>
  );
}

export function ProductDetailPage({ params }: { params: Promise<{ slug: string }> }) {
  return (
    <main className={styles.page}>
      <a className="skip-link" href="#main-content">Saltar al contenido principal</a>
      <ProductHeader backHref="/productos" backLabel="← Ver catálogo" />
      <div id="main-content" tabIndex={-1}>
        <Suspense fallback={<p className={styles.state} role="status">Cargando ficha…</p>}>
          <ProductContent params={params} />
        </Suspense>
      </div>
    </main>
  );
}
