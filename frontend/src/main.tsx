import { StrictMode, Suspense } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import './index.css'
import App from './App.tsx'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 1000 * 60 * 5, // 5 minutes
      retry: 2,
    },
  },
})

// Loading fallback component
function LoadingFallback() {
  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50">
      <div className="text-center">
        <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-blue-600 mx-auto mb-4"></div>
        <p className="text-gray-600">Loading Wiki4AI...</p>
      </div>
    </div>
  )
}

try {
  const rootElement = document.getElementById('root')
  
  if (!rootElement) {
    throw new Error('Root element #root not found in DOM')
  }

  const root = createRoot(rootElement)
  
  root.render(
    <StrictMode>
      <QueryClientProvider client={queryClient}>
        <Suspense fallback={<LoadingFallback />}>
          <App />
        </Suspense>
      </QueryClientProvider>
    </StrictMode>,
  )

  console.log('✅ Wiki4AI application started successfully')
} catch (error) {
  const rootElement = document.getElementById('root')
  if (rootElement) {
    const errorObj = error instanceof Error ? error : new Error(String(error))
    rootElement.innerHTML = `
      <div class="min-h-screen flex items-center justify-center bg-red-50 p-8">
        <div class="max-w-md text-center">
          <h1 class="text-2xl font-bold text-red-600 mb-4">Application Error</h1>
          <p class="text-gray-700 mb-4">Failed to start application:</p>
          <pre class="bg-white p-4 rounded border border-red-200 text-left overflow-auto max-h-64 text-sm text-red-800">${errorObj.message}</pre>
          <button onclick="window.location.reload()" class="mt-4 px-4 py-2 bg-red-600 text-white rounded hover:bg-red-700">Reload Page</button>
        </div>
      </div>
    `
  }
  console.error('❌ Failed to start Wiki4AI:', error)
}
