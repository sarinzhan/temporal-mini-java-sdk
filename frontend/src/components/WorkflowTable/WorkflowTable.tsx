import { useMemo } from 'react';
import {
  flexRender,
  getCoreRowModel,
  useReactTable,
  type ColumnDef,
  type RowSelectionState,
  type SortingState,
} from '@tanstack/react-table';
import {
  Box,
  Checkbox,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TablePagination,
  TableRow,
  TableSortLabel,
} from '@mui/material';
import { useNavigate } from 'react-router-dom';
import type { Workflow, WorkflowPage } from '../../types/workflow';
import type { LastActivity } from '../../types/activity';
import { buildWorkflowColumns, SORTABLE_COLUMNS } from './columns';

interface Props {
  data?: WorkflowPage;
  lastActivities: Record<number, LastActivity>;
  page: number;
  pageSize: number;
  pageSizeOptions: number[];
  sorting: SortingState;
  rowSelection: RowSelectionState;
  onPageChange: (page: number) => void;
  onPageSizeChange: (size: number) => void;
  onSortingChange: (s: SortingState) => void;
  onRowSelectionChange: (s: RowSelectionState) => void;
}

export function WorkflowTable({
  data, lastActivities, page, pageSize, pageSizeOptions, sorting, rowSelection,
  onPageChange, onPageSizeChange, onSortingChange, onRowSelectionChange,
}: Props) {
  const navigate = useNavigate();

  const columns = useMemo<ColumnDef<Workflow>[]>(() => [
    {
      id: 'select',
      header: ({ table }) => (
        <Checkbox
          size="small"
          indeterminate={table.getIsSomeRowsSelected()}
          checked={table.getIsAllRowsSelected()}
          onChange={table.getToggleAllRowsSelectedHandler()}
          onClick={(e) => e.stopPropagation()}
        />
      ),
      cell: ({ row }) => (
        <Checkbox
          size="small"
          checked={row.getIsSelected()}
          onChange={row.getToggleSelectedHandler()}
          onClick={(e) => e.stopPropagation()}
        />
      ),
    },
    ...buildWorkflowColumns(lastActivities),
  ], [lastActivities]);

  const table = useReactTable({
    data: data?.content ?? [],
    columns,
    state: { sorting, rowSelection },
    manualSorting: true,
    enableRowSelection: true,
    getRowId: (row) => String(row.id),
    onSortingChange: (updater) =>
      onSortingChange(typeof updater === 'function' ? updater(sorting) : updater),
    onRowSelectionChange: (updater) =>
      onRowSelectionChange(typeof updater === 'function' ? updater(rowSelection) : updater),
    getCoreRowModel: getCoreRowModel(),
  });

  return (
    <Paper>
      <TableContainer>
        <Table size="small">
          <TableHead>
            {table.getHeaderGroups().map((hg) => (
              <TableRow key={hg.id}>
                {hg.headers.map((h) => {
                  const sortable = SORTABLE_COLUMNS.has(h.column.id);
                  const sort = sorting.find((s) => s.id === h.column.id);
                  return (
                    <TableCell
                      key={h.id}
                      sx={{ fontWeight: 600, color: 'text.secondary', textTransform: 'uppercase', fontSize: 11 }}
                      sortDirection={sort ? (sort.desc ? 'desc' : 'asc') : false}
                    >
                      {sortable
                        ? (
                          <TableSortLabel
                            active={!!sort}
                            direction={sort?.desc ? 'desc' : 'asc'}
                            onClick={() => h.column.toggleSorting()}
                          >
                            {flexRender(h.column.columnDef.header, h.getContext())}
                          </TableSortLabel>
                        )
                        : flexRender(h.column.columnDef.header, h.getContext())}
                    </TableCell>
                  );
                })}
              </TableRow>
            ))}
          </TableHead>
          <TableBody>
            {table.getRowModel().rows.length === 0 ? (
              <TableRow>
                <TableCell colSpan={columns.length}>
                  <Box sx={{ textAlign: 'center', py: 6, color: 'text.disabled' }}>No workflows found</Box>
                </TableCell>
              </TableRow>
            ) : (
              table.getRowModel().rows.map((row) => (
                <TableRow
                  key={row.id}
                  hover
                  selected={row.getIsSelected()}
                  sx={{ cursor: 'pointer' }}
                  onClick={() => navigate(`/workflows/${row.original.id}`)}
                >
                  {row.getVisibleCells().map((cell) => (
                    <TableCell key={cell.id}>
                      {flexRender(cell.column.columnDef.cell, cell.getContext())}
                    </TableCell>
                  ))}
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </TableContainer>
      <TablePagination
        component="div"
        count={data?.totalElements ?? 0}
        page={page}
        rowsPerPage={pageSize}
        rowsPerPageOptions={pageSizeOptions}
        onPageChange={(_, p) => onPageChange(p)}
        onRowsPerPageChange={(e) => onPageSizeChange(Number(e.target.value))}
      />
    </Paper>
  );
}
