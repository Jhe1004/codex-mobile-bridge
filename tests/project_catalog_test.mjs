import assert from "node:assert/strict";
import { enrichThreadList, resolveProjectId, OTHER_PROJECT_ID } from "../mac_bridge/src/project_catalog.mjs";

const catalog = {
  projects: [
    { id: "p1", name: "hyde", rootPaths: ["/work/hyde"], order: 0 },
    { id: "p2", name: "计算机相关", rootPaths: ["/work/computer"], order: 1 },
  ],
  assignments: {
    assigned: { projectKind: "local", projectId: "p2" },
  },
};

assert.equal(resolveProjectId({ id: "assigned", cwd: "/somewhere/else" }, catalog), "p2");
assert.equal(resolveProjectId({ id: "nested", cwd: "/work/hyde/01_stage" }, catalog), "p1");
assert.equal(resolveProjectId({ id: "other", cwd: "/tmp" }, catalog), OTHER_PROJECT_ID);

const result = enrichThreadList({ data: [
  { id: "assigned", cwd: "/somewhere/else", name: "会话二" },
  { id: "nested", cwd: "/work/hyde", name: "会话一" },
  { id: "nested", cwd: "/work/hyde", name: "重复会话" },
  { id: "other", cwd: "/tmp", name: "其他" },
] }, catalog);

assert.deepEqual(result.data.map((thread) => thread.projectId), ["p2", "p1", OTHER_PROJECT_ID]);
assert.deepEqual(result.projects.map((project) => [project.id, project.threadCount]), [
  ["p1", 1],
  ["p2", 1],
  [OTHER_PROJECT_ID, 1],
]);

console.log("project catalog mapping: ok");
