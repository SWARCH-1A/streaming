import { Badge } from '@/src/components/atoms/Badge';

interface TagListProps {
  tags: readonly { id: string; name: string }[];
}
export function TagList({ tags }: TagListProps) {
  return (
    <div className="row">
      {tags.map((tag) => (
        <Badge key={tag.id}>{tag.name}</Badge>
      ))}
    </div>
  );
}
