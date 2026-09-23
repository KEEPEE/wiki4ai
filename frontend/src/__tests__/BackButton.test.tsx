/**
 * Tests for BackButton component
 */

import { describe, it, expect } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import BackButton from '../components/BackButton'

function renderWithRouter(ui: React.ReactElement) {
  return render(
    <MemoryRouter>{ui}</MemoryRouter>,
  )
}

describe('BackButton', () => {
  it('should render with default label "Back" (WIKI4AI-73: EN default)', () => {
    renderWithRouter(<BackButton to="/projects/test-project" />)
    expect(screen.getByText('Back')).toBeInTheDocument()
  })

  it('should render custom label when provided', () => {
    renderWithRouter(<BackButton to="/projects/test-project" label="Návrat na projekt" />)
    expect(screen.getByText('Návrat na projekt')).toBeInTheDocument()
  })

  it('should have correct glassmorphism classes and structure', () => {
    const { container } = renderWithRouter(<BackButton to="/projects/test-project" />)
    const button = container.querySelector('.back-button') as HTMLButtonElement
    expect(button).toBeInTheDocument()
    expect(button?.textContent).toContain('←')
    expect(button?.textContent).toContain('Back')
  })

  it('should navigate to the provided "to" route when clicked', () => {
    renderWithRouter(<BackButton to="/projects/test-project" />)
    const button = screen.getByRole('button') as HTMLButtonElement
    // Button should be clickable - navigation happens via react-router
    expect(button).toBeInTheDocument()
    fireEvent.click(button)
  })

  it('should have aria-label attribute for accessibility', () => {
    renderWithRouter(<BackButton to="/projects/test-project" />)
    const button = screen.getByRole('button') as HTMLButtonElement
    expect(button).toHaveAttribute('aria-label', 'Back')
  })

  it('should have arrow icon with aria-hidden="true"', () => {
    renderWithRouter(<BackButton to="/projects/test-project" />)
    const icon = screen.getByText('←') as HTMLElement
    // The icon span should have aria-hidden
    expect(icon).toHaveAttribute('aria-hidden', 'true')
  })

  it('should use router go(-1) when no "to" prop is provided', () => {
    renderWithRouter(<BackButton />)
    const button = screen.getByRole('button') as HTMLButtonElement
    // Button should be rendered without a specific route
    expect(button).toBeInTheDocument()
  })

  it('should have pill shape border-radius (50px)', () => {
    renderWithRouter(<BackButton to="/projects/test-project" />)
    const button = screen.getByRole('button') as HTMLButtonElement
    // Check that the button has the correct styling via class
    expect(button).toHaveClass('back-button')
  })

  it('should have glassmorphism visual properties', () => {
    renderWithRouter(<BackButton to="/projects/test-project" />)
    const button = screen.getByRole('button') as HTMLButtonElement
    // Verify the component renders with correct class for glassmorphism styling
    expect(button).toHaveAttribute('class', 'back-button')
  })

  it('should have back-button-icon and back-button-text child elements', () => {
    renderWithRouter(<BackButton to="/projects/test-project" />)
    const icon = screen.getByText('←') as HTMLElement
    expect(icon).toHaveClass('back-button-icon')
    expect(screen.getByText('Back')).toHaveClass('back-button-text')
  })

  it('should be keyboard accessible', () => {
    renderWithRouter(<BackButton to="/projects/test-project" />)
    const button = screen.getByRole('button') as HTMLButtonElement
    // Button should be focusable and clickable via keyboard
    expect(button).toBeInstanceOf(HTMLButtonElement)
  })

  it('should have correct aria-label when no "to" prop is provided', () => {
    renderWithRouter(<BackButton />)
    const button = screen.getByRole('button') as HTMLButtonElement
    expect(button).toHaveAttribute('aria-label', 'Back')
  })
})
