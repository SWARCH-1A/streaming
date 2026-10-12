import styles from './Metric.module.css';

interface MetricProps {
  label: string;
  value: string;
}
export function Metric({ label, value }: MetricProps) {
  return (
    <div className={styles.metric}>
      <span className="eyebrow">{label}</span>
      <strong>{value}</strong>
    </div>
  );
}
