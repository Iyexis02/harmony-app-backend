# Music Dating App - Complete Frontend Implementation Guide

## 🎵 Welcome Frontend Developer!

This guide provides complete instructions for building the frontend of our music-based dating application. The app matches people based on their music taste, powered by Spotify integration and intelligent genre preference extraction.

---

## 📋 Quick Start

### Prerequisites

```bash
# Required
- Node.js 18+ and npm/yarn
- React 18+ (or Next.js)
- Backend running on http://localhost:8080

# Recommended
- TypeScript
- Tailwind CSS or styled-components
- Zustand or Redux for state management
- React Router for navigation
```

### Project Setup

```bash
# Clone repository
git clone <repository-url>
cd dating-app-frontend

# Install dependencies
npm install

# Set up environment variables
cp .env.example .env
# Edit .env with your configuration

# Start development server
npm run dev
```

### Environment Variables

Create `.env` file:

```env
# API Configuration
VITE_API_BASE_URL=http://localhost:8080
VITE_BACKEND_TEST_MODE=true  # Use test endpoints without auth

# Spotify OAuth
VITE_SPOTIFY_CLIENT_ID=your_spotify_client_id
VITE_SPOTIFY_CLIENT_SECRET=your_spotify_client_secret
VITE_SPOTIFY_REDIRECT_URI=http://localhost:3000/auth/spotify/callback

# App Configuration
VITE_APP_NAME=MusicMatch
VITE_MIN_MATCH_SCORE=60
```

---

## 🏗️ Architecture Overview

### Phase Breakdown

The app is built in 3 phases:

| Phase | Status | Backend | Frontend | User-Facing |
|-------|--------|---------|----------|-------------|
| **Phase 1: Database Foundation** | ✅ Complete | ✅ Done | 🟡 Admin Only | ❌ No |
| **Phase 2: Genre Extraction** | ✅ Complete | ✅ Done | ⏳ TODO | ✅ Yes |
| **Phase 3: Matching Algorithm** | ⏸️ Planned | ⏸️ Coming | ⏸️ Coming | ✅ Yes |

### Navigation Structure

```
/
├── /onboarding
│   ├── /signup
│   ├── /login
│   └── /music              ← Phase 2
│
├── /profile
│   ├── /edit
│   └── /preferences        ← Phase 2
│
├── /discover               ← Phase 3 (Coming)
├── /matches                ← Phase 3 (Coming)
├── /chat                   ← Phase 4 (Future)
│
└── /admin                  ← Phase 1 (Dev Only)
    └── /phase1
```

---

## 📚 Implementation Guides

Each phase has a dedicated implementation guide:

### Phase 1: Database Foundation
**File:** `FRONTEND_PHASE1_INSTRUCTIONS.md`

**Summary:**
- Admin dashboard for verifying database setup
- Genre browser (100+ music genres)
- Database statistics viewer
- **Status:** Backend complete, frontend optional (dev tools only)

**User-Facing:** ❌ No (admin/testing only)

**Priority:** 🟡 Low (nice to have for debugging)

---

### Phase 2: Genre Extraction & Preferences
**File:** `FRONTEND_PHASE2_INSTRUCTIONS.md`

**Summary:**
- Onboarding: "Connect Spotify" or "Manual Selection"
- Spotify OAuth integration
- Manual genre selection interface (100+ genres)
- User profile showing music preferences
- Preference management (add/remove/edit)

**User-Facing:** ✅ Yes (critical for user profiles)

**Priority:** 🔴 HIGH (required for matching to work)

**Components to Build:**
1. `MusicPreferencesPage` - Onboarding entry point
2. `SpotifyConnectCard` - OAuth flow
3. `ManualSelectionCard` - Genre picker
4. `MusicPreferencesSection` - Profile display
5. `EditPreferencesPage` - Manage preferences

---

### Phase 3: Matching Algorithm
**File:** `FRONTEND_PHASE3_INSTRUCTIONS.md`

**Summary:**
- Swipe interface (Tinder-style)
- Match score calculation (0-100%)
- Match breakdown visualization
- Matches list
- Match notifications
- Analytics dashboard

**User-Facing:** ✅ Yes (core dating feature)

**Priority:** 🔴 HIGH (coming soon)

**Status:** ⏸️ Backend not yet built - this is a roadmap

**Components to Build (when ready):**
1. `SwipeCard` - Swipeable match cards
2. `MatchScoreBreakdown` - Detailed compatibility view
3. `MatchesList` - All mutual matches
4. `MatchNotification` - "It's a Match!" popup
5. `AnalyticsPage` - Personal statistics

---

## 🎯 Development Priorities

### Week 1-2: Phase 2 (Highest Priority)

✅ **Must Build:**
1. Music preferences onboarding flow
2. Spotify OAuth integration
3. Manual genre selection
4. Profile music section
5. Preference editing

🎨 **Design Focus:**
- Mobile-first responsive design
- Smooth animations
- Clear visual hierarchy
- Accessible (WCAG 2.1 AA)

### Week 3-4: Phase 2 Polish + Phase 1 Tools

✅ **Should Build:**
1. Polish Phase 2 UI/UX
2. Add loading states and error handling
3. Implement analytics tracking
4. Build admin tools (Phase 1) for debugging

### Future: Phase 3 (When Backend Ready)

⏳ **Will Build:**
1. Discover/swipe interface
2. Match scoring visualization
3. Matches list and chat
4. Real-time notifications

---

## 🔗 API Integration

### Authentication

All production endpoints require JWT authentication:

```jsx
const token = localStorage.getItem('auth_token');

fetch('http://localhost:8080/api/v1/preferences/genres', {
  headers: {
    'Authorization': `Bearer ${token}`,
    'Content-Type': 'application/json'
  }
})
```

### Test vs Production Endpoints

**Development (No Auth):**
```
GET  /api/test/phase1/genres
POST /api/test/phase2/extract-mock
GET  /api/test/phase2/top-genres
```

**Production (JWT Required):**
```
GET    /api/v1/preferences/genres
POST   /api/v1/preferences/genres
DELETE /api/v1/preferences/genres/{genreName}
POST   /api/v1/preferences/genres/sync
```

⚠️ **Important:** Test endpoints have NO authentication. Remove before production!

---

## 🎨 Design System

### Colors

Based on Spotify branding + modern dating app aesthetics:

```css
/* Primary Colors */
--spotify-green: #1db954;
--spotify-dark: #191414;
--spotify-light: #f5f5f5;

/* Gradient (Onboarding) */
--gradient-primary: linear-gradient(135deg, #667eea 0%, #764ba2 100%);

/* Semantic Colors */
--success: #1db954;
--error: #e74c3c;
--warning: #f39c12;
--info: #3498db;

/* Text */
--text-primary: #333;
--text-secondary: #666;
--text-muted: #999;

/* Background */
--bg-primary: #ffffff;
--bg-secondary: #f5f5f5;
--bg-card: #ffffff;
```

### Typography

```css
/* Headings */
h1 { font-size: 42px; font-weight: bold; }
h2 { font-size: 32px; font-weight: bold; }
h3 { font-size: 24px; font-weight: 600; }

/* Body */
body { font-size: 16px; line-height: 1.5; }
small { font-size: 14px; }

/* Font Stack */
font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Roboto',
             'Oxygen', 'Ubuntu', sans-serif;
```

### Component Patterns

**Cards:**
```css
.card {
  background: white;
  border-radius: 12px;
  padding: 20px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.1);
}
```

**Buttons:**
```css
.btn-primary {
  background: #1db954;
  color: white;
  padding: 12px 30px;
  border-radius: 25px;
  border: none;
  font-weight: bold;
}

.btn-secondary {
  background: #667eea;
  /* ... */
}
```

**Genre Chips:**
```css
.genre-chip {
  background: #e8eaf6;
  color: #667eea;
  padding: 8px 20px;
  border-radius: 20px;
  font-size: 14px;
}

.genre-chip.selected {
  background: #667eea;
  color: white;
}
```

---

## 📱 Responsive Design

All components must work on:

✅ **Mobile:** 320px - 767px (primary target)
✅ **Tablet:** 768px - 1023px
✅ **Desktop:** 1024px+

### Mobile-First Example

```css
/* Mobile (default) */
.genre-grid {
  grid-template-columns: repeat(2, 1fr);
  gap: 10px;
}

/* Tablet */
@media (min-width: 768px) {
  .genre-grid {
    grid-template-columns: repeat(3, 1fr);
    gap: 15px;
  }
}

/* Desktop */
@media (min-width: 1024px) {
  .genre-grid {
    grid-template-columns: repeat(4, 1fr);
    gap: 20px;
  }
}
```

---

## 🧩 Recommended Tech Stack

### Core Framework

**Option 1: React + Vite (Recommended)**
```bash
npm create vite@latest dating-app-frontend -- --template react
cd dating-app-frontend
npm install
```

**Option 2: Next.js (For SSR)**
```bash
npx create-next-app@latest dating-app-frontend
cd dating-app-frontend
```

### Essential Libraries

```bash
# Routing
npm install react-router-dom

# State Management
npm install zustand  # Recommended (simple)
# or
npm install @reduxjs/toolkit react-redux  # Alternative (complex)

# HTTP Client
npm install axios

# Form Handling
npm install react-hook-form

# Styling
npm install tailwindcss  # Utility-first CSS
# or
npm install styled-components  # CSS-in-JS

# Animations
npm install framer-motion  # For swipe animations

# Icons
npm install react-icons

# Date Handling
npm install date-fns
```

### Optional but Useful

```bash
# Drag and Drop (for ranking genres)
npm install @dnd-kit/core @dnd-kit/sortable

# Charts (for analytics)
npm install recharts

# Image Optimization
npm install react-lazy-load-image-component

# Toast Notifications
npm install react-hot-toast
```

---

## 🧪 Testing

### Unit Tests

```bash
npm install --save-dev vitest @testing-library/react @testing-library/jest-dom
```

```jsx
// Example test: GenreChip.test.jsx
import { render, fireEvent } from '@testing-library/react';
import GenreChip from './GenreChip';

test('toggles selection on click', () => {
  const onToggle = jest.fn();
  const { getByText } = render(
    <GenreChip genre="Rock" selected={false} onToggle={onToggle} />
  );

  fireEvent.click(getByText('Rock'));
  expect(onToggle).toHaveBeenCalledWith('Rock');
});
```

### Integration Tests

```bash
npm install --save-dev @playwright/test
```

```javascript
// Example: onboarding.spec.js
import { test, expect } from '@playwright/test';

test('complete manual genre selection', async ({ page }) => {
  await page.goto('http://localhost:3000/onboarding/music');

  // Click manual selection
  await page.click('text=Select Genres Manually');

  // Select 3 genres
  await page.click('text=Rock');
  await page.click('text=Jazz');
  await page.click('text=Pop');

  // Save
  await page.click('text=Save');

  // Should redirect to profile
  await expect(page).toHaveURL('/profile');
});
```

---

## 🚀 Deployment

### Build for Production

```bash
# Build
npm run build

# Preview production build locally
npm run preview

# Deploy to Vercel (recommended for React/Next.js)
npm install -g vercel
vercel
```

### Environment Variables (Production)

```env
VITE_API_BASE_URL=https://api.yourdomain.com
VITE_SPOTIFY_CLIENT_ID=production_client_id
VITE_SPOTIFY_REDIRECT_URI=https://yourdomain.com/auth/spotify/callback
```

---

## 🔒 Security Checklist

✅ **Authentication:**
- [ ] Store JWT in httpOnly cookies (not localStorage)
- [ ] Implement token refresh logic
- [ ] Clear tokens on logout

✅ **API Security:**
- [ ] Validate all user inputs
- [ ] Sanitize data before rendering
- [ ] Use HTTPS in production
- [ ] Implement rate limiting on client side

✅ **Spotify OAuth:**
- [ ] Never expose client secret in frontend
- [ ] Use state parameter to prevent CSRF
- [ ] Validate redirect URI

✅ **Data Privacy:**
- [ ] Don't log sensitive user data
- [ ] Respect user's privacy preferences
- [ ] Clear cache on logout

---

## 📊 Performance Optimization

### Code Splitting

```jsx
import { lazy, Suspense } from 'react';

const DiscoverPage = lazy(() => import('./pages/DiscoverPage'));

function App() {
  return (
    <Suspense fallback={<LoadingSpinner />}>
      <DiscoverPage />
    </Suspense>
  );
}
```

### Image Optimization

```jsx
import { LazyLoadImage } from 'react-lazy-load-image-component';

<LazyLoadImage
  src={photo.url}
  alt={user.name}
  effect="blur"
  placeholderSrc={photo.thumbnailUrl}
/>
```

### Memoization

```jsx
import { useMemo } from 'react';

const sortedGenres = useMemo(() => {
  return genres.sort((a, b) => b.weight - a.weight);
}, [genres]);
```

---

## ♿ Accessibility

### Keyboard Navigation

```jsx
<button
  onKeyDown={(e) => {
    if (e.key === 'Enter' || e.key === ' ') {
      handleClick();
    }
  }}
  aria-label="Like this profile"
>
  ❤️ Like
</button>
```

### Screen Reader Support

```jsx
<div role="status" aria-live="polite">
  {loading ? 'Loading preferences...' : `Loaded ${preferences.length} preferences`}
</div>

<button aria-label={`Select ${genre.name} genre`}>
  {genre.displayName}
</button>
```

### Focus Management

```jsx
import { useEffect, useRef } from 'react';

function Modal({ isOpen }) {
  const closeButtonRef = useRef();

  useEffect(() => {
    if (isOpen) {
      closeButtonRef.current?.focus();
    }
  }, [isOpen]);

  return (
    <div role="dialog" aria-modal="true">
      <button ref={closeButtonRef}>Close</button>
    </div>
  );
}
```

---

## 🐛 Debugging Tips

### React DevTools

```bash
# Install React DevTools browser extension
# Chrome: https://chrome.google.com/webstore (search "React Developer Tools")
# Firefox: https://addons.mozilla.org (search "React DevTools")
```

### Network Debugging

```jsx
// Log all API calls
const originalFetch = window.fetch;
window.fetch = async (...args) => {
  console.log('API Call:', args[0]);
  const response = await originalFetch(...args);
  console.log('Response:', response.status);
  return response;
};
```

### Backend Connection Test

```jsx
// Test backend connectivity
fetch('http://localhost:8080/api/test/phase1/genres')
  .then(res => res.json())
  .then(data => console.log('✅ Backend connected:', data))
  .catch(err => console.error('❌ Backend connection failed:', err));
```

---

## 📝 Development Workflow

### Daily Workflow

```bash
# 1. Pull latest changes
git pull origin main

# 2. Create feature branch
git checkout -b feature/phase2-genre-selection

# 3. Start development server
npm run dev

# 4. Make changes, test locally

# 5. Run tests
npm run test

# 6. Commit changes
git add .
git commit -m "feat: Add manual genre selection component"

# 7. Push to remote
git push origin feature/phase2-genre-selection

# 8. Create pull request
```

### Git Commit Convention

```
feat: Add new feature
fix: Bug fix
docs: Documentation update
style: Code style changes (formatting)
refactor: Code refactoring
test: Add or update tests
chore: Build process or auxiliary tool changes
```

---

## 🆘 Getting Help

### Documentation Links

- **Phase 1 Details:** `FRONTEND_PHASE1_INSTRUCTIONS.md`
- **Phase 2 Details:** `FRONTEND_PHASE2_INSTRUCTIONS.md`
- **Phase 3 Details:** `FRONTEND_PHASE3_INSTRUCTIONS.md`
- **Backend API Docs:** See backend's `MATCHING_PHASE1_SUMMARY.md`, `PHASE2_GENRE_EXTRACTION.md`

### Common Issues

**Issue:** "Cannot connect to backend"
```bash
# Solution: Check backend is running
curl http://localhost:8080/api/test/phase1/genres
# Should return JSON with genres
```

**Issue:** "Spotify OAuth redirect not working"
```bash
# Solution: Check redirect URI matches exactly
# Spotify Dashboard: https://developer.spotify.com/dashboard
# Must match: http://localhost:3000/auth/spotify/callback
```

**Issue:** "JWT token expired"
```jsx
// Solution: Implement token refresh
const refreshToken = async () => {
  const response = await fetch('/api/auth/refresh', {
    method: 'POST',
    headers: { 'Authorization': `Bearer ${refreshToken}` }
  });
  const { accessToken } = await response.json();
  localStorage.setItem('auth_token', accessToken);
};
```

---

## ✅ Launch Checklist

Before going to production:

### Phase 2 (Genre Preferences)
- [ ] Onboarding flow works for both Spotify and manual users
- [ ] Spotify OAuth connects successfully
- [ ] Manual genre selection allows 3-15 genres
- [ ] Profile displays music preferences correctly
- [ ] Users can edit preferences
- [ ] Re-syncing Spotify preserves manual preferences
- [ ] Mobile responsive on all screens
- [ ] Accessible (keyboard navigation works)
- [ ] Loading states implemented
- [ ] Error handling covers all edge cases

### Security
- [ ] Remove test endpoints or add authentication
- [ ] API keys stored in environment variables
- [ ] HTTPS enabled in production
- [ ] JWT tokens secured (httpOnly cookies)
- [ ] Input validation on all forms

### Performance
- [ ] Images optimized and lazy loaded
- [ ] Code splitting implemented
- [ ] Bundle size < 500KB (initial load)
- [ ] Time to Interactive < 3 seconds

### Testing
- [ ] Unit tests passing (>80% coverage)
- [ ] Integration tests passing
- [ ] Manual testing on iOS and Android
- [ ] Cross-browser testing (Chrome, Safari, Firefox)

---

## 🎉 Summary

You now have everything you need to build the frontend!

**Start Here:**
1. Read `FRONTEND_PHASE2_INSTRUCTIONS.md` (highest priority)
2. Set up your development environment
3. Build the onboarding flow
4. Implement Spotify OAuth
5. Create manual genre selection
6. Add profile display

**Next:**
- Phase 1 admin tools (optional)
- Phase 3 matching UI (when backend ready)

**Questions?**
- Check the detailed phase documentation
- Review backend API documentation
- Test endpoints using curl or Postman

**Good luck building an amazing music-based dating app! 🎵💕**
