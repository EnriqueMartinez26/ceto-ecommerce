import Link from "next/link";
import { Suspense } from "react";
import { getCatalog } from "../server/catalog-api";
import type { StoreProduct, StoreProductPage } from "../model";
import { ProductAvailability } from "./ProductAvailability";
import { ProductHeader } from "./ProductHeader";
import { ProductPrice } from "./ProductPrice";
import styles from "./catalog.module.css";

const DEFAULT_PAGE_SIZE = 20;
const MAX_PAGE = 1_000_000;

type PageSearchParams = Promise<Record<string, string | string[] | undefined>>;

type Pagination = { page: number; size: number };

function ProductType({ product }: { product: StoreProduct }) {
  if (product.kind === "BASE") return <>Producto base</>;
  return <>Combo de {product.comboUnits} unidades</>;
}

function singleInteger(value: string | string[] | undefined, fallback: number, maximum: number): number | null {
  if (value === undefined) return fallback;
  if (Array.isArray(value) || !/^\d+$/.test(value)) return null;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed >= 0 && parsed <= maximum ? parsed : null;
}

function parsePagination(params: Record<string, string | string[] | undefined>): Pagination | null {
  const page = singleInteger(params.page, 0, MAX_PAGE);
  const size = singleInteger(params.size, DEFAULT_PAGE_SIZE, 100);
  if (page === null || size === null || size < 1) return null;
  return { page, size };
}

function PaginationLinks({ data }: { data: StoreProductPage }) {
  const pageHref = (page: number) => `/productos?page=${page}&size=${data.size}`;
  return (
    <nav className={styles.pagination} aria-label="Paginación del catálogo">
      {data.page > 0 ? (
        <Link className={styles.paginationLink} href={pageHref(data.page - 1)} rel="prev">← Anterior</Link>
      ) : <span aria-hidden="true" />}
      <span className={styles.pageCurrent} aria-current="page">Página {data.page + 1}</span>
      {data.hasNext && data.page < MAX_PAGE ? (
        <Link className={styles.paginationLink} href={pageHref(data.page + 1)} rel="next">Siguiente →</Link>
      ) : <span aria-hidden="true" />}
    </nav>
  );
}

async function CatalogContent({ page, size }: Pagination) {
  const catalog = await getCatalog(page, size);
  if (catalog.status !== "ready") {
    return (
      <div className={styles.state} role="alert">
        <p>El catálogo no está disponible en este momento.</p>
        <Link className={styles.paginationLink} href={`/productos?page=${page}&size=${size}`}>Volver a intentar</Link>
      </div>
    );
  }
  const data = catalog.data;
  if (data.items.length === 0) {
    return (
      <div className={styles.state} role="status">
        <p>{page === 0 ? "Todavía no hay productos publicados." : "No hay productos en esta página."}</p>
        {page > 0 && <Link className={styles.paginationLink} href={`/productos?page=${page - 1}&size=${size}`}>← Volver a la página anterior</Link>}
      </div>
    );
  }

  return (
    <>
      <ul className={styles.grid}>
        {data.items.map((product) => (
          <li className={styles.card} key={product.id}>
            <span className={styles.kicker}><ProductType product={product} /></span>
            <h2>{product.name}</h2>
            <ProductPrice
              product={product}
              className={styles.prices}
              priceClassName={styles.price}
              compareClassName={styles.compare}
            />
            <ProductAvailability
              availability={product.availability}
              availableClassName={styles.available}
              unavailableClassName={styles.unavailable}
            />
            <Link className={styles.link} href={`/productos/${encodeURIComponent(product.slug)}`}>Ver detalles <span aria-hidden="true">↗</span></Link>
          </li>
        ))}
      </ul>
      <PaginationLinks data={data} />
    </>
  );
}

async function CatalogRoute({ searchParams }: { searchParams: PageSearchParams }) {
  const pagination = parsePagination(await searchParams);
  if (!pagination) {
    return (
      <p className={styles.state} role="alert">
        Los parámetros de paginación no son válidos. <Link href="/productos?page=0&size=20">Volver al catálogo</Link>
      </p>
    );
  }

  return <CatalogContent {...pagination} />;
}

export function CatalogPage({ searchParams }: { searchParams: PageSearchParams }) {
  return (
    <main className={styles.page}>
      <a className="skip-link" href="#main-content">Saltar al contenido principal</a>
      <ProductHeader backHref="/" backLabel="← Volver al inicio" />
      <section id="main-content" className={styles.intro} tabIndex={-1}>
        <p className={styles.kicker}>01 / CATÁLOGO</p>
        <h1>Productos <em>publicados.</em></h1>
        <p>Consultá las opciones disponibles y su precio actual.</p>
      </section>
      <Suspense fallback={<p className={styles.state} role="status">Cargando catálogo…</p>}>
        <CatalogRoute searchParams={searchParams} />
      </Suspense>
      <p className={styles.note}>La compra online aún no está habilitada.</p>
    </main>
  );
}
