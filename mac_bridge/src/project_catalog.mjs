import { readFileSync } from "node:fs";

export const OTHER_PROJECT_ID = "__unassigned__";

export function readProjectCatalog(path) {
  const state = JSON.parse(readFileSync(path, "utf8"));
  const localProjects = state["local-projects"] ?? {};
  const requestedOrder = Array.isArray(state["project-order"]) ? state["project-order"] : [];
  const orderedIds = [...requestedOrder, ...Object.keys(localProjects).filter((id) => !requestedOrder.includes(id))];
  const projects = orderedIds.flatMap((id, order) => {
    const project = localProjects[id];
    if (!project || !Array.isArray(project.rootPaths) || project.rootPaths.length === 0) return [];
    return [{
      id,
      name: String(project.name || project.rootPaths[0].split("/").filter(Boolean).at(-1) || "未命名项目"),
      rootPaths: project.rootPaths.map(String),
      order,
    }];
  });
  return {
    projects,
    assignments: state["thread-project-assignments"] ?? {},
  };
}

function pathBelongsToRoot(cwd, root) {
  return cwd === root || cwd.startsWith(`${root}/`);
}

export function resolveProjectId(thread, catalog) {
  const assignedId = catalog.assignments?.[thread.id]?.projectId;
  if (assignedId && catalog.projects.some((project) => project.id === assignedId)) return assignedId;
  const cwd = String(thread.cwd ?? "");
  let match = null;
  for (const project of catalog.projects) {
    for (const root of project.rootPaths) {
      if (pathBelongsToRoot(cwd, root) && (!match || root.length > match.root.length)) {
        match = { id: project.id, root };
      }
    }
  }
  return match?.id ?? OTHER_PROJECT_ID;
}

export function enrichThreadList(result, catalog) {
  const seen = new Set();
  const data = (result.data ?? [])
    .filter((thread) => {
      if (!thread?.id || seen.has(thread.id)) return false;
      seen.add(thread.id);
      return true;
    })
    .map((thread) => ({
      id: thread.id,
      name: thread.name ?? null,
      title: thread.title ?? null,
      preview: String(thread.preview ?? "").slice(0, 1000),
      cwd: String(thread.cwd ?? ""),
      createdAt: thread.createdAt ?? thread.created_at ?? 0,
      updatedAt: thread.updatedAt ?? thread.updated_at ?? 0,
      status: thread.status ?? null,
      mobileActivity: thread.mobileActivity ?? "idle",
      completionRevision: thread.completionRevision ?? "",
      completedAt: thread.completedAt ?? 0,
      isPinned: thread.isPinned ?? false,
      projectId: resolveProjectId(thread, catalog),
    }));
  const counts = new Map();
  for (const thread of data) counts.set(thread.projectId, (counts.get(thread.projectId) ?? 0) + 1);
  const projects = catalog.projects.map((project) => ({
    ...project,
    threadCount: counts.get(project.id) ?? 0,
  }));
  const unassignedCount = counts.get(OTHER_PROJECT_ID) ?? 0;
  if (unassignedCount > 0) {
    projects.push({
      id: OTHER_PROJECT_ID,
      name: "其他会话",
      rootPaths: [],
      order: projects.length,
      threadCount: unassignedCount,
    });
  }
  return { ...result, data, projects };
}
