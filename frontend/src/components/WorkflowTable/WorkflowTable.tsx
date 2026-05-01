import { useMemo } from 'react';
import {
  flexRender,
  getCoreRowModel,
  useReactTable,
} from '@tanstack/react-table';
import {
  Box,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TablePagination,
  TableRow,
} from '@mui/material';
import { useNavigate } from 'react-router-dom';
import type { RuntimeMap, WorkflowPage } from '../../types/workflow';
import type { LastActivity } from '../../types/activity';
import { buildWorkflowColumns } from './columns';

interface Props {
  data?: WorkflowPage;
  runtime: RuntimeMap;
  lastActivities: Record<number, LastActivity>;
  page: number;
  pageSize: number;
  onPageChange: (page: number) => void;
}

export function WorkflowTable({ data, runtime, lastActivities, page, pageSize, onPageChange }: Props) {
  const navigate = useNavigate();
  const columns = useMemo(
    () => buildWorkflowColumns(runtime, lastActivities),
    [runtime, lastActivities],
  );

  const table = useReactTable({
    data: data?.content ?? [],
    columns,
    getCoreRowModel: getCoreRowModel(),
  });

  return (
    <Paper>
      <TableContainer>
        <Table size="small">
          <TableHead>
            {table.getHeaderGroups().map((hg) => (
              <TableRow key={hg.id}>
                {hg.headers.map((h) => (
                  <TableCell key={h.id} sx={{ fontWeight: 600, color: 'text.secondary', textTransform: 'uppercase', fontSize: 11 }}>
                    {flexRender(h.column.columnDef.header, h.getContext())}
                  </TableCell>
                ))}
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
        rowsPerPageOptions={[pageSize]}
        onPageChange={(_, p) => onPageChange(p)}
      />
    </Paper>
  );
}
