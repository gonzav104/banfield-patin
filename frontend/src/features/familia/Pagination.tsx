import { Button } from '@/components/ui/button';

interface PaginationProps {
  page: number;
  totalPages: number;
  total: number;
  onPrevious: () => void;
  onNext: () => void;
}

export function Pagination({ page, totalPages, total, onPrevious, onNext }: PaginationProps) {
  return (
    <nav aria-label="Paginación" className="flex items-center justify-between gap-3 text-sm">
      <Button variant="outline" disabled={page === 0} onClick={onPrevious}>
        Anterior
      </Button>
      <p className="text-center text-muted">
        Página {page + 1} de {totalPages} · {total} en total
      </p>
      <Button variant="outline" disabled={page + 1 >= totalPages} onClick={onNext}>
        Siguiente
      </Button>
    </nav>
  );
}
