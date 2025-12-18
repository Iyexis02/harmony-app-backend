# Frontend-Backend Claude Agent Sync Strategy

**Purpose**: Coordination protocol for multiple Claude instances working on frontend and backend
**Last Updated**: 2025-11-26
**Status**: 🟢 Active

---

## 🎯 Core Strategy

This project uses **two separate Claude instances**:

1. **Frontend Claude** (VS Code) - Works on Next.js frontend
2. **Backend Claude** (IntelliJ/VS Code) - Works on Spring Boot backend

Both agents stay synchronized by reading and updating shared markdown documentation files.

---

## 📚 Knowledge Base Files

### Priority 1: **MUST READ** Before Every Session

| File | Owner | When to Read | When to Update |
|------|-------|--------------|----------------|
| **SYNC_STRATEGY.md** | Both | First time only | When workflow changes |
| **FRONTEND_PROJECT_STATUS.md** | Frontend | Every frontend session | After frontend changes |
| **BACKEND_PROJECT_STATUS.md** | Backend | Every backend session | After backend changes |

### Priority 2: Read When Relevant

| File | Purpose | When to Read |
|------|---------|--------------|
| **GENRES_ENDPOINT_DOCUMENTATION.md** | Genres API guide | When integrating genres feature |
| **IMPLEMENTATION_SUMMARY.md** | Recent changes | When resuming work |
| **CLAUDE_SYNC_GUIDE.md** | General sync guide | When confused about sync |

---

## 🔄 The Sync Workflow

### **Frontend Claude (Next.js - VS Code)**

#### Every Session Starts With:
```
Read FRONTEND_PROJECT_STATUS.md and BACKEND_PROJECT_STATUS.md to understand
the current state, then help me [YOUR FRONTEND TASK]
```

#### Why Read Backend Status?
- Know what endpoints are available
- Understand request/response formats
- See what DTOs to use
- Check authentication requirements

#### After Completing Work:
```
Update FRONTEND_PROJECT_STATUS.md with the work we just completed,
following the "Recent Work Completed" format
```

---

### **Backend Claude (Spring Boot - IntelliJ/VS Code)**

#### Every Session Starts With:
```
Read BACKEND_PROJECT_STATUS.md and FRONTEND_PROJECT_STATUS.md to understand
the current state, then help me [YOUR BACKEND TASK]
```

#### Why Read Frontend Status?
- Know what the frontend expects
- Understand frontend constraints
- See what features are implemented
- Check for integration issues

#### After Completing Work:
```
Update BACKEND_PROJECT_STATUS.md with the work we just completed,
following the "Recent Work Completed" format
```

---

## 📋 Responsibilities by Agent

### Frontend Claude Responsibilities

**Primary Document**: `FRONTEND_PROJECT_STATUS.md`

**Update When**:
- Adding new components
- Implementing new features
- Fixing frontend bugs
- Updating dependencies
- Changing configuration
- Integrating new APIs

**Must Include**:
- What changed (files and line numbers)
- Why it changed
- How to use the new feature
- Any new dependencies
- Testing instructions

**Example Update**:
```markdown
### X. Genres Pre-selection Integration ✅

**Feature**: Integrated genres endpoint into MusicPreferencesStep

**Implementation**:
- Created `fetchSuggestedGenres()` server action
- Updated MusicPreferencesStep to pre-select genres
- Added loading state and error handling

**Files Modified**:
1. **app/serverActions/spotify.ts** - Added fetchSuggestedGenres function
2. **app/onboarding/components/steps/MusicPreferencesStep.tsx** (lines 45-60)
   - Fetches genres on mount
   - Pre-selects top 5 genres

**API Used**: `GET /api/v1/user/genres`
```

---

### Backend Claude Responsibilities

**Primary Document**: `BACKEND_PROJECT_STATUS.md`

**Update When**:
- Adding new endpoints
- Modifying DTOs
- Changing services
- Updating database models
- Fixing backend bugs
- Changing authentication

**Must Include**:
- New/modified endpoints with examples
- Request/response formats
- What the frontend needs to do
- Migration notes (if database changed)
- Environment variable changes

**Example Update**:
```markdown
### X. Genres Endpoint Implementation ✅

**Feature**: New endpoint to fetch pre-selected genres

**Implementation**:
- Added `GET /api/v1/user/genres` endpoint
- Extracts genres from user's top Spotify artists
- Returns deduplicated, sorted list

**Files Modified**:
1. **SpotifyService.java** - Added getGenresFromTopArtists() method
2. **SpotifyServiceImpl.java** (lines 195-212) - Implementation
3. **UserController.java** (lines 135-166) - New endpoint

**API Specification**:
- Endpoint: `GET /api/v1/user/genres`
- Query Params: `limit` (default: 20), `time_range` (default: medium_term)
- Response: `List<String>` - Array of genre names
- Auth: Required (JWT)

**Frontend Integration Required**:
- Create server action to call endpoint
- Pre-select returned genres in MusicPreferencesStep
- See GENRES_ENDPOINT_DOCUMENTATION.md for details
```

---

## 🔀 Cross-Agent Communication

### When Backend Adds a New Endpoint

1. **Backend Claude** creates the endpoint
2. **Backend Claude** updates `BACKEND_PROJECT_STATUS.md`:
   - Add endpoint to "API Endpoints" section
   - Add request/response examples
   - Note in "Recent Work Completed"
   - Create feature-specific docs if complex
3. **Frontend Claude** (next session):
   - Reads `BACKEND_PROJECT_STATUS.md`
   - Sees new endpoint
   - Implements integration
   - Updates `FRONTEND_PROJECT_STATUS.md`

---

### When Frontend Needs a New Feature

1. **Frontend Claude** identifies need
2. **Frontend Claude** updates `FRONTEND_PROJECT_STATUS.md`:
   - Add to "Next Steps" or "Pending Features"
   - Describe what backend support is needed
3. **Backend Claude** (next session):
   - Reads `FRONTEND_PROJECT_STATUS.md`
   - Sees feature request
   - Implements backend support
   - Updates `BACKEND_PROJECT_STATUS.md`
4. **Frontend Claude** (next session):
   - Reads `BACKEND_PROJECT_STATUS.md`
   - Sees implementation
   - Completes frontend integration

---

## 📊 Status Tracking Conventions

### Status Indicators

Use these consistently in both status files:

| Indicator | Meaning | Usage |
|-----------|---------|-------|
| ✅ | Complete | Feature fully implemented |
| ⏳ | In Progress | Currently being worked on |
| ❌ | Blocked/Issue | Has problems or dependencies |
| 🟡 | Partial | Partially implemented |
| 🔴 | Critical | Urgent issue |
| 🟢 | Good | No issues |

### Section Headers

Both status files use the same structure:

```markdown
## 🎯 Recent Work Completed (2025-11-26)

### 1. [Feature Name] ✅

**Problem/Feature**: Description

**Implementation**:
- Bullet points

**Files Modified**:
1. **filename** (lines X-Y) - Description

**Documentation**: Link to related docs (if applicable)

**Frontend/Backend Integration Required**: What's next
```

---

## 🗂️ File Organization

### Project Root Files

```
dating/
├── SYNC_STRATEGY.md                    # This file - Sync coordination
├── BACKEND_PROJECT_STATUS.md           # Backend status (Backend Claude owns)
├── FRONTEND_PROJECT_STATUS.md          # Frontend status (Frontend Claude owns)
├── CLAUDE_SYNC_GUIDE.md                # General sync guide
├── GENRES_ENDPOINT_DOCUMENTATION.md    # Feature-specific docs
├── IMPLEMENTATION_SUMMARY.md           # Temporary summary
└── [other feature docs]
```

### When to Create New Docs

**Create feature-specific documentation when**:
- Adding a complex API endpoint
- Implementing a major feature
- Making architectural changes
- Frontend needs detailed integration guide

**Naming convention**: `[FEATURE]_DOCUMENTATION.md`

**Examples**:
- `GENRES_ENDPOINT_DOCUMENTATION.md`
- `MATCHING_ALGORITHM_DOCUMENTATION.md`
- `MESSAGING_SYSTEM_DOCUMENTATION.md`

---

## 📝 Examples: Real Scenarios

### Scenario 1: Backend Adds Genres Endpoint

**Backend Claude Session**:

1. **Start**:
   ```
   Read BACKEND_PROJECT_STATUS.md and FRONTEND_PROJECT_STATUS.md,
   then help me add an endpoint to fetch pre-selected genres for onboarding
   ```

2. **Work**: Implements endpoint, service method, tests

3. **Document**:
   ```
   Create comprehensive documentation for the genres endpoint in
   GENRES_ENDPOINT_DOCUMENTATION.md, then update BACKEND_PROJECT_STATUS.md
   with this new feature
   ```

4. **Result**:
   - `BACKEND_PROJECT_STATUS.md` updated (Recent Work section)
   - `GENRES_ENDPOINT_DOCUMENTATION.md` created
   - API endpoint section updated

---

**Frontend Claude Session (Next Day)**:

1. **Start**:
   ```
   Read FRONTEND_PROJECT_STATUS.md and BACKEND_PROJECT_STATUS.md,
   then help me integrate the genres endpoint into the MusicPreferencesStep
   ```

2. **Claude Response**:
   - "I see from BACKEND_PROJECT_STATUS.md that a new `/api/v1/user/genres` endpoint was added"
   - "I also found detailed integration guide in GENRES_ENDPOINT_DOCUMENTATION.md"
   - "Let me implement the integration..."

3. **Work**: Creates server action, updates component

4. **Document**:
   ```
   Update FRONTEND_PROJECT_STATUS.md with the genres integration work
   we just completed
   ```

---

### Scenario 2: Frontend Discovers Backend Bug

**Frontend Claude Session**:

1. **Discovers**: Genres endpoint returns duplicates

2. **Document in FRONTEND_PROJECT_STATUS.md**:
   ```markdown
   ## 🐛 Known Issues & Blockers

   ### Backend: Genres Endpoint Returns Duplicates ❌

   **Issue**: `/api/v1/user/genres` sometimes returns duplicate genre names
   **Impact**: Frontend shows duplicate chips
   **Workaround**: Frontend deduplicates on client side
   **Needs**: Backend fix in SpotifyServiceImpl.java
   ```

---

**Backend Claude Session (Next)**:

1. **Start**:
   ```
   Read BACKEND_PROJECT_STATUS.md and FRONTEND_PROJECT_STATUS.md,
   then help me fix issues
   ```

2. **Claude Response**:
   - "I see from FRONTEND_PROJECT_STATUS.md there's a bug in the genres endpoint"
   - "Let me investigate and fix it..."

3. **Work**: Fixes the bug

4. **Document in BACKEND_PROJECT_STATUS.md**:
   ```markdown
   ### Bug Fix: Genres Endpoint Duplicates ✅

   **Problem**: Genres were not being deduplicated correctly
   **Fix**: Added `.distinct()` to stream in SpotifyServiceImpl.java:203
   **Files Modified**: SpotifyServiceImpl.java (line 203)
   **Status**: Resolved
   ```

5. **Also Update FRONTEND_PROJECT_STATUS.md**:
   ```
   Also update FRONTEND_PROJECT_STATUS.md to remove the bug from
   Known Issues section since it's now fixed
   ```

---

## ✅ Best Practices

### DO ✅

1. **Always read both status files** before starting work
2. **Update your primary status file** after completing work
3. **Cross-reference line numbers** when mentioning code changes
4. **Create feature docs** for complex implementations
5. **Use status indicators** consistently (✅ ⏳ ❌)
6. **Include code examples** in API documentation
7. **Mention frontend/backend requirements** explicitly
8. **Link related documentation** files

### DON'T ❌

1. **Don't skip reading status files** - You'll duplicate work
2. **Don't update the wrong status file** - Frontend updates FRONTEND, Backend updates BACKEND
3. **Don't leave stale information** - Remove/update old entries
4. **Don't create too many docs** - Consolidate related info
5. **Don't forget to document** - Future Claude depends on it
6. **Don't use vague descriptions** - Be specific with file paths and changes
7. **Don't duplicate information** - Reference other docs instead
8. **Don't forget cross-references** - Link frontend and backend changes

---

## 🎓 Quick Reference

### Frontend Claude Quick Start
```
Read FRONTEND_PROJECT_STATUS.md and BACKEND_PROJECT_STATUS.md,
then help me [FRONTEND TASK]
```

### Backend Claude Quick Start
```
Read BACKEND_PROJECT_STATUS.md and FRONTEND_PROJECT_STATUS.md,
then help me [BACKEND TASK]
```

### After Completing Work (Frontend)
```
Update FRONTEND_PROJECT_STATUS.md with what we just completed
```

### After Completing Work (Backend)
```
Update BACKEND_PROJECT_STATUS.md with what we just completed
```

### When Blocked by Other Side
Add to **Known Issues & Blockers** section with:
- Clear description
- Impact on your side
- What the other side needs to do

---

## 🔍 Finding Information

### Frontend Claude Needs:

| Need | Look Here |
|------|-----------|
| What endpoints exist? | `BACKEND_PROJECT_STATUS.md` → "API Endpoints" |
| Request/response format? | `BACKEND_PROJECT_STATUS.md` → Specific endpoint section |
| What enums to use? | `BACKEND_PROJECT_STATUS.md` → "Enums" |
| Authentication flow? | `BACKEND_PROJECT_STATUS.md` → "Authentication & Security" |
| Recent backend changes? | `BACKEND_PROJECT_STATUS.md` → "Recent Work Completed" |

### Backend Claude Needs:

| Need | Look Here |
|------|-----------|
| What's on the frontend? | `FRONTEND_PROJECT_STATUS.md` → "Implementation Status" |
| What do they expect? | `FRONTEND_PROJECT_STATUS.md` → "Next Steps" |
| Frontend issues? | `FRONTEND_PROJECT_STATUS.md` → "Known Issues & Blockers" |
| Integration examples? | `FRONTEND_PROJECT_STATUS.md` → Recent work sections |
| Recent frontend changes? | `FRONTEND_PROJECT_STATUS.md` → "Recent Work Completed" |

---

## 🎯 Success Criteria

**This sync strategy is working when**:

✅ No duplicate work between frontend and backend
✅ Both agents aware of recent changes
✅ Integration happens smoothly without back-and-forth
✅ Bugs are documented and fixed efficiently
✅ Both status files stay current
✅ New features are well-documented
✅ Cross-agent communication is seamless

---

## 📍 File Locations

**All sync files are at project root**:

```
C:\Users\MladenHangi\Downloads\dating\dating\
├── SYNC_STRATEGY.md
├── BACKEND_PROJECT_STATUS.md
├── FRONTEND_PROJECT_STATUS.md
├── CLAUDE_SYNC_GUIDE.md
└── [feature-specific docs]
```

**Accessible from both**:
- IntelliJ (backend work)
- VS Code (frontend work)

---

## 🚀 Getting Started

### First Time Setup (For Any Claude Instance)

```
Read SYNC_STRATEGY.md, BACKEND_PROJECT_STATUS.md, and
FRONTEND_PROJECT_STATUS.md to understand the complete project state
```

### Regular Session (Frontend)

```
Read FRONTEND_PROJECT_STATUS.md and BACKEND_PROJECT_STATUS.md,
then help me [YOUR TASK]
```

### Regular Session (Backend)

```
Read BACKEND_PROJECT_STATUS.md and FRONTEND_PROJECT_STATUS.md,
then help me [YOUR TASK]
```

---

**Remember**: Documentation is the bridge between Claude instances. The more you document, the better the collaboration! 🤝

---

**Last Updated**: 2025-11-26
**Status**: 🟢 Active and Working
