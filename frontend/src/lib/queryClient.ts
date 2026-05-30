import { QueryCache, QueryClient } from '@tanstack/react-query';
import { toast } from '@/components/ui/Toaster';

export const queryClient = new QueryClient({
  queryCache: new QueryCache({
    onError: (err) => toast(err instanceof Error ? err.message : String(err)),
  }),
  defaultOptions: {
    queries: {
      refetchOnWindowFocus: false,
      retry: 1,
      staleTime: 5_000,
    },
    mutations: {
      onError: (err) => toast(err instanceof Error ? err.message : String(err)),
    },
  },
});
