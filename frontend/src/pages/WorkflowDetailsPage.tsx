import { useState } from 'react';
import { Alert, Box, Button, CircularProgress, Container, Paper, Stack, Typography } from '@mui/material';
import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import EditIcon from '@mui/icons-material/Edit';
import { Link, useParams } from 'react-router-dom';
import { Header } from '../components/Header/Header';
import { StatusBadge } from '../components/StatusBadge/StatusBadge';
import { WorkflowControls } from '../components/WorkflowControls/WorkflowControls';
import { ActivityList } from '../components/ActivityList/ActivityList';
import { JsonViewer } from '../components/JsonViewer/JsonViewer';
import { NextRunCell } from '../components/WorkflowTable/NextRunCell';
import { PayloadEditDialog } from '../components/PayloadEditDialog/PayloadEditDialog';
import { useWorkflow } from '../hooks/useWorkflow';
import { useActivities } from '../hooks/useActivities';
import { useEditPayload } from '../hooks/useEditPayload';
import { fmtDate } from '../utils/format';

const PAYLOAD_EDITABLE = new Set(['NEW', 'STOPPED', 'FAILED']);

export function WorkflowDetailsPage() {
  const { id: idParam } = useParams<{ id: string }>();
  const id = Number(idParam);

  const { data: workflow, isLoading, error } = useWorkflow(id);
  const { data: activities = [] } = useActivities(id);
  const { setWorkflowPayload } = useEditPayload(id);
  const [editOpen, setEditOpen] = useState(false);

  return (
    <>
      <Header />
      <Container maxWidth="xl" sx={{ py: 3 }}>
        <Stack spacing={2}>
          <Button component={Link} to="/workflows" startIcon={<ArrowBackIcon />} sx={{ alignSelf: 'flex-start' }}>
            Back to list
          </Button>

          {isLoading && <CircularProgress />}
          {error && <Alert severity="error">{(error as Error).message}</Alert>}

          {workflow && (
            <>
              <Paper sx={{ p: 2.5 }}>
                <Stack direction="row" alignItems="center" spacing={2} flexWrap="wrap" sx={{ mb: 2 }}>
                  <Typography variant="h6" sx={{ fontWeight: 700 }}>Workflow #{workflow.id}</Typography>
                  <StatusBadge state={workflow.state} />
                  <Typography sx={{ fontFamily: 'SFMono-Regular, Consolas, monospace', color: 'primary.main' }}>
                    {workflow.workflowType}
                  </Typography>
                  <Box sx={{ flex: 1 }} />
                  <WorkflowControls workflow={workflow} />
                </Stack>

                <Box sx={{
                  display: 'grid',
                  gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
                  gap: 1.5,
                }}>
                  <Meta label="Created"  value={fmtDate(workflow.createdAt)} />
                  <Meta label="Started"  value={fmtDate(workflow.startedAt)} />
                  <Meta label="Next run">
                    <NextRunCell workflow={workflow} />
                  </Meta>
                  <Meta label="Activities" value={`${activities.length}`} />
                </Box>

                {workflow.errorMessage && (
                  <Alert severity="error" sx={{ mt: 2 }}>
                    <Typography variant="caption" sx={{ fontWeight: 700, textTransform: 'uppercase' }}>
                      Workflow error
                    </Typography>
                    <Typography variant="body2">{workflow.errorMessage}</Typography>
                  </Alert>
                )}

                <Box sx={{ mt: 2 }}>
                  <Stack direction="row" alignItems="center" spacing={1} sx={{ mb: 0.5 }}>
                    <Typography variant="caption" sx={{ fontWeight: 700, textTransform: 'uppercase', color: 'text.disabled' }}>
                      Initial payload
                    </Typography>
                    {PAYLOAD_EDITABLE.has(workflow.state) && (
                      <Button size="small" startIcon={<EditIcon fontSize="small" />}
                              onClick={() => setEditOpen(true)} sx={{ minWidth: 0 }}>
                        Edit
                      </Button>
                    )}
                  </Stack>
                  <JsonViewer raw={workflow.nextPayload} title="Initial payload" />
                </Box>
              </Paper>

              <Typography variant="overline" sx={{ color: 'text.secondary', fontWeight: 700 }}>
                Activities
              </Typography>
              <ActivityList workflowId={workflow.id} activities={activities} />

              <PayloadEditDialog
                open={editOpen}
                title={`Edit input — workflow #${workflow.id}`}
                initialValue={workflow.nextPayload}
                saving={setWorkflowPayload.isPending}
                onClose={() => setEditOpen(false)}
                onSave={(payload) => {
                  setWorkflowPayload.mutate(payload, {
                    onSuccess: () => setEditOpen(false),
                  });
                }}
              />
            </>
          )}
        </Stack>
      </Container>
    </>
  );
}

function Meta({ label, value, children }: { label: string; value?: string; children?: React.ReactNode }) {
  return (
    <Box sx={{ bgcolor: 'action.hover', borderRadius: 2, px: 1.75, py: 1.25 }}>
      <Typography variant="caption" sx={{ color: 'text.disabled', fontWeight: 600, textTransform: 'uppercase', letterSpacing: 0.5 }}>
        {label}
      </Typography>
      <Box sx={{ mt: 0.5 }}>
        {children ?? <Typography variant="body2">{value}</Typography>}
      </Box>
    </Box>
  );
}
