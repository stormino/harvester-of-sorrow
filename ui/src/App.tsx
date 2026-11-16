import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createBrowserRouter, Navigate, RouterProvider } from 'react-router';
import { ToastProvider } from './components/Toast';
import { TaskStreamProvider } from './live/TaskStream';
import { DownloadsScreen } from './screens/DownloadsScreen';
import { LibraryScreen } from './screens/LibraryScreen';
import { SearchScreen } from './screens/SearchScreen';
import { SettingsScreen } from './screens/SettingsScreen';
import { XdccScreen } from './screens/XdccScreen';
import { RouteError } from './shell/RouteError';
import { Shell } from './shell/Shell';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: 1, refetchOnWindowFocus: false },
  },
});

const router = createBrowserRouter([
  {
    element: <Shell />,
    errorElement: <RouteError />,
    children: [
      { index: true, element: <SearchScreen /> },
      { path: 'xdcc', element: <XdccScreen /> },
      { path: 'downloads', element: <DownloadsScreen /> },
      { path: 'library', element: <LibraryScreen /> },
      { path: 'settings', element: <SettingsScreen /> },
      { path: '*', element: <Navigate to="/" replace /> },
    ],
  },
]);

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <TaskStreamProvider>
          <RouterProvider router={router} />
        </TaskStreamProvider>
      </ToastProvider>
    </QueryClientProvider>
  );
}
