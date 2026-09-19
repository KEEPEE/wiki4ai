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
    <div>
      <div>
        <div></div>
        <p>Loading Wiki4AI...</p>
      </div>
    </div>
  )
}

// Global error handler to catch any unhandled errors
window.addEventListener('error', (e) => {
  console.error('Global error caught:', e.error || e.message)
})
window.addEventListener('unhandledrejection', (e) => {
  console.error('Unhandled promise rejection:', e.reason)
})

console.log('🚀 Wiki4AI: Starting application...')
console.log('📍 Current URL:', window.location.href)

try {
  const rootElement = document.getElementById('root')
  
  if (!rootElement) {
    throw new Error('Root element #root not found in DOM')
  }

  console.log('✅ Root element found, creating React root...')
  const root = createRoot(rootElement)
  
  console.log('🎨 Rendering App component...')
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
      <div>
        <div>
          <h1>Application Error</h1>
          <p>Failed to start application:</p>
          <pre>${errorObj.message}</pre>
          <button onclick="window.location.reload()">Reload Page</button>
        </div>
      </div>
    `
  }
  console.error('❌ Failed to start Wiki4AI:', error)
}
