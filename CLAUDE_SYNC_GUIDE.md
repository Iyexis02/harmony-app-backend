# Claude Multi-Agent Sync Guide

**Purpose**: How to keep multiple Claude instances synchronized using markdown files as shared memory
**Project**: Dating App (Music-based)
**Last Updated**: 2025-11-26

---

## 🎯 Core Concept

**Markdown files = Shared memory between Claude instances**

Since Claude instances cannot directly communicate, we use markdown documentation files as a "shared brain" that all instances read from and write to.

---

## 📚 Knowledge Base Files

Your project uses these files as shared memory:

| File | Priority | Purpose | Update Frequency |
|------|----------|---------|------------------|
| `FRONTEND_PROJECT_STATUS.md` | 🔴 **CRITICAL** | Master status document | After every major change |
| `CLAUDE_SYNC_GUIDE.md` | 🔴 **CRITICAL** | This file - sync instructions | When workflow changes |
| `IMPLEMENTATION_SUMMARY.md` | 🟡 Medium | Recent feature details | After new features |
| `GENRES_ENDPOINT_DOCUMENTATION.md` | 🟡 Medium | Specific API documentation | When APIs are created |
| `CLAUDE.md` | 🟢 Low | Backend API specification | When API contract changes |

---

## 🔄 The Sync Workflow

### **Every Time You Open a New Claude Instance**

Copy and paste this prompt:

```
Read FRONTEND_PROJECT_STATUS.md to understand the current project state,
then help me with [YOUR SPECIFIC TASK]
```

**Why this works**:
- Claude will read the status file first
- It will understand what's already done
- It won't duplicate work
- It will know about recent changes

### **Example Prompts**

#### Starting Frontend Work
```
Read FRONTEND_PROJECT_STATUS.md and GENRES_ENDPOINT_DOCUMENTATION.md,
then help me integrate the genres endpoint into the MusicPreferencesStep component
```

#### Starting Backend Work
```
Read FRONTEND_PROJECT_STATUS.md and CLAUDE.md,
then help me add validation to the genres endpoint
```

#### Debugging
```
Read FRONTEND_PROJECT_STATUS.md, then help me debug why
Cloudinary uploads are failing in the PhotosStep component
```

---

## ✍️ How to Update Shared Memory

### **Rule 1: Update After Completing Work**

After Claude completes a significant task, ask:

```
Update FRONTEND_PROJECT_STATUS.md with the work we just completed,
following the existing format in the "Recent Work Completed" section
```

### **Rule 2: Create Feature-Specific Docs**

For new features or APIs, create dedicated documentation:

```
Create a documentation file for the [FEATURE NAME] that explains
the implementation and how other Claude instances can use it
```

**Example**: `GENRES_ENDPOINT_DOCUMENTATION.md` was created to explain the new genres API.

### **Rule 3: Keep Docs Current**

If you find outdated information:

```
I notice FRONTEND_PROJECT_STATUS.md says X, but we just changed it to Y.
Please update the file to reflect the current state.
```

---

## 📋 Step-by-Step: Syncing Two Claude Instances

### **Scenario**: Backend Claude (IntelliJ) + Frontend Claude (VS Code)

#### **Backend Claude Session (IntelliJ)**

1. **Start Session**:
   ```
   Read FRONTEND_PROJECT_STATUS.md and help me add a new endpoint
   to fetch user's favorite artists
   ```

2. **During Work**:
   - Claude implements the backend code
   - Tests the endpoint
   - Creates API documentation

3. **End Session**:
   ```
   Create documentation for the new artists endpoint and update
   FRONTEND_PROJECT_STATUS.md with what we completed
   ```

   **Result**:
   - `ARTISTS_ENDPOINT_DOCUMENTATION.md` created
   - `FRONTEND_PROJECT_STATUS.md` updated with new endpoint details

#### **Frontend Claude Session (VS Code) - Later That Day**

1. **Start Session**:
   ```
   Read FRONTEND_PROJECT_STATUS.md and ARTISTS_ENDPOINT_DOCUMENTATION.md,
   then help me integrate the artists endpoint into the onboarding flow
   ```

2. **What Happens**:
   - ✅ Claude reads the status file
   - ✅ Claude sees the new endpoint documentation
   - ✅ Claude knows exactly how to call the API
   - ✅ Claude integrates without asking basic questions

3. **End Session**:
   ```
   Update FRONTEND_PROJECT_STATUS.md to show the frontend integration
   is complete
   ```

---

## 🏗️ Document Structure Guidelines

### **FRONTEND_PROJECT_STATUS.md Structure**

```markdown
## 🎯 Recent Work Completed (2025-11-26)

### [NUMBER]. [FEATURE NAME] ✅

**Problem**: [What problem was solved]

**Implementation**:
- Bullet point summary
- Key changes
- Important details

**Files Modified**:
1. **filename.ext** (lines X-Y) - What changed
2. **filename2.ext** - What changed

**Documentation**:
- Link to related docs

**Frontend Integration Required**: [If applicable]
- What needs to be done next
```

### **Feature-Specific Documentation Structure**

```markdown
# [Feature Name] Documentation

**Date**: 2025-11-26
**Status**: ✅ Complete

## Overview
Brief description

## API Endpoint (if applicable)
Full specification

## Implementation Details
How it works

## Frontend Integration Guide
Code examples

## Testing
How to test
```

---

## 🎨 Best Practices

### **DO ✅**

1. **Always read status first**: `Read FRONTEND_PROJECT_STATUS.md` before starting work
2. **Update after major changes**: Keep docs current
3. **Use specific file references**: "See UserController.java:135-166"
4. **Create feature docs**: For complex implementations
5. **Include code examples**: In documentation files
6. **Add timestamps**: Date all updates
7. **Mark completion**: Use ✅ and ⏳ status indicators

### **DON'T ❌**

1. **Don't skip reading status**: You'll duplicate work
2. **Don't leave stale docs**: Update when things change
3. **Don't create too many files**: Consolidate related info
4. **Don't use vague descriptions**: Be specific about changes
5. **Don't forget line numbers**: They help locate code quickly
6. **Don't duplicate information**: Reference other docs instead

---

## 📖 Real Example from This Project

### **What Happened Today (2025-11-26)**

**Backend Claude** (this session):
1. Read `FRONTEND_PROJECT_STATUS.md`
2. Implemented genres endpoint
3. Created `GENRES_ENDPOINT_DOCUMENTATION.md`
4. Created `IMPLEMENTATION_SUMMARY.md`
5. Updated `FRONTEND_PROJECT_STATUS.md` (lines 73-102)

**Frontend Claude** (your next session in VS Code):
1. Should read: `FRONTEND_PROJECT_STATUS.md` + `GENRES_ENDPOINT_DOCUMENTATION.md`
2. Will know: Genres endpoint exists at `/api/v1/user/genres`
3. Will see: Complete TypeScript integration examples
4. Can immediately: Start implementing frontend without asking backend questions

---

## 🚀 Quick Start Templates

### **Template 1: Starting a New Session**

```
Read FRONTEND_PROJECT_STATUS.md to understand the current state,
then help me [SPECIFIC TASK]
```

### **Template 2: After Completing Work**

```
Update FRONTEND_PROJECT_STATUS.md with the following completed work:
- [What was done]
- [Files changed]
- [What's next]

Follow the existing format in the "Recent Work Completed" section.
```

### **Template 3: Creating Feature Documentation**

```
Create a comprehensive documentation file for [FEATURE NAME] that includes:
- API specification (if applicable)
- Implementation details
- Frontend integration guide with TypeScript examples
- Testing instructions

Save it as [FEATURE_NAME]_DOCUMENTATION.md
```

### **Template 4: Syncing Before Starting**

```
Read FRONTEND_PROJECT_STATUS.md and list:
1. The most recent changes
2. What's currently in progress
3. Any blockers or issues

Then help me [YOUR TASK]
```

---

## 🔍 Finding Information Quickly

### **Where to Look for What**

| Need to Know | Look Here |
|--------------|-----------|
| What was done recently? | `FRONTEND_PROJECT_STATUS.md` → "Recent Work Completed" |
| What's the project structure? | `FRONTEND_PROJECT_STATUS.md` → "Key Files & Locations" |
| How to call an API? | `[FEATURE]_DOCUMENTATION.md` files |
| What's the backend spec? | `CLAUDE.md` |
| Environment variables? | `FRONTEND_PROJECT_STATUS.md` → "Environment Configuration" |
| Known issues? | `FRONTEND_PROJECT_STATUS.md` → "Known Issues & Blockers" |
| Next steps? | `FRONTEND_PROJECT_STATUS.md` → "Next Steps / Roadmap" |

---

## 🎯 Common Scenarios

### **Scenario 1: Two Agents Working on Related Features**

**Agent A** (Backend - adds genres endpoint):
- Implements backend
- Creates `GENRES_ENDPOINT_DOCUMENTATION.md`
- Updates `FRONTEND_PROJECT_STATUS.md`

**Agent B** (Frontend - integrates genres):
- Reads `FRONTEND_PROJECT_STATUS.md` first
- Reads `GENRES_ENDPOINT_DOCUMENTATION.md`
- Has all context needed
- Implements frontend
- Updates `FRONTEND_PROJECT_STATUS.md`

### **Scenario 2: Debugging Across Sessions**

**Session 1** (encounters bug):
```
Create a DEBUG_CLOUDINARY_UPLOAD.md file documenting:
- The bug behavior
- What I've tried
- Current hypothesis
- Next steps to investigate
```

**Session 2** (different Claude instance):
```
Read DEBUG_CLOUDINARY_UPLOAD.md and help me continue debugging
the Cloudinary upload issue
```

### **Scenario 3: Handoff Between Frontend and Backend**

**Backend Session**:
```
I've implemented the backend for user profile updates.
Create documentation for the frontend team explaining how to use the new endpoints.
```

**Frontend Session** (next day):
```
Read FRONTEND_PROJECT_STATUS.md and PROFILE_UPDATE_DOCUMENTATION.md,
then implement the profile update UI
```

---

## 📱 File Naming Conventions

### **Permanent Documentation**
- `FRONTEND_PROJECT_STATUS.md` - Master status
- `CLAUDE_SYNC_GUIDE.md` - This guide
- `CLAUDE.md` - Backend API spec

### **Feature Documentation**
- `[FEATURE]_DOCUMENTATION.md` - API/feature guides
- Example: `GENRES_ENDPOINT_DOCUMENTATION.md`

### **Temporary Context**
- `CURRENT_ISSUE_[DATE].md` - Active debugging
- `DEBUG_[ISSUE].md` - Specific bug investigation
- Example: `DEBUG_CLOUDINARY_UPLOAD_20251126.md`

### **Summaries**
- `IMPLEMENTATION_SUMMARY.md` - Recent changes summary
- Delete or archive after info is merged into `FRONTEND_PROJECT_STATUS.md`

---

## 🧹 Maintenance

### **Weekly**
- Review `FRONTEND_PROJECT_STATUS.md`
- Archive completed `DEBUG_*` files
- Merge temporary docs into permanent ones

### **After Major Milestones**
- Update `FRONTEND_PROJECT_STATUS.md` version history
- Archive old implementation summaries
- Consolidate feature docs if needed

### **When Starting New Features**
- Create feature-specific documentation
- Update roadmap in `FRONTEND_PROJECT_STATUS.md`

---

## ⚠️ Important Notes

1. **Claude can't remember between sessions**: Files are the ONLY way to persist knowledge
2. **Always update after completing work**: Future Claude instances depend on it
3. **Be specific with line numbers**: Helps locate code quickly
4. **Use status indicators**: ✅ ⏳ ❌ make status clear at a glance
5. **Include code examples**: Especially for APIs and integrations
6. **Date everything**: Timestamps help track project evolution

---

## 🎓 Key Takeaway

**Think of markdown files as a persistent brain that all Claude instances share.**

- Before starting: **READ** the brain
- While working: **THINK** about what to document
- After finishing: **WRITE** to the brain

This creates a knowledge loop that keeps all Claude instances synchronized! 🔄

---

## 📍 File Location

**This file**: `CLAUDE_SYNC_GUIDE.md` (root directory)

**File path**: `C:\Users\MladenHangi\Downloads\dating\dating\CLAUDE_SYNC_GUIDE.md`

**Local URL**: `file:///C:/Users/MladenHangi/Downloads/dating/dating/CLAUDE_SYNC_GUIDE.md`

**Relative path** (from project root): `./CLAUDE_SYNC_GUIDE.md`

---

## 🔗 Quick Links

When opening a new Claude instance, share these file paths:

1. **Status File**: `C:\Users\MladenHangi\Downloads\dating\dating\FRONTEND_PROJECT_STATUS.md`
2. **This Guide**: `C:\Users\MladenHangi\Downloads\dating\dating\CLAUDE_SYNC_GUIDE.md`
3. **Recent Work**: `C:\Users\MladenHangi\Downloads\dating\dating\IMPLEMENTATION_SUMMARY.md`

---

**Remember**: The more you update the docs, the better future Claude instances will perform! 🚀
