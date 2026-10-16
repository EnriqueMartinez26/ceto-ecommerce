import Link from "next/link";
import styles from "./ProductHeader.module.css";

type ProductHeaderProps = {
  backHref: string;
  backLabel: string;
};

export function ProductHeader({ backHref, backLabel }: ProductHeaderProps) {
  return (
    <header className={styles.header}>
      <Link className={styles.brand} href="/">MEJOR VIDA <b>CETO</b></Link>
      <Link className={styles.back} href={backHref}>{backLabel}</Link>
    </header>
  );
}
