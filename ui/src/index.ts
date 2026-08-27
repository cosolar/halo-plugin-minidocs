import { definePlugin } from "@halo-dev/ui-shared";
import { markRaw } from "vue";
import { IconBookRead } from "@halo-dev/components";
import "virtual:uno.css";
import "./styles/index.css";
import KnowledgeBaseList from "./views/KnowledgeBaseList.vue";
import KnowledgeBaseDetail from "./views/KnowledgeBaseDetail.vue";

export default definePlugin({
  routes: [
    {
      parentName: "Root",
      route: {
        path: "/minidocs",
        name: "KnowledgeBases",
        component: KnowledgeBaseList,
        meta: {
          title: "知识库",
          searchable: true,
          permissions: ["plugin:halo-plugin-minidocs:knowledgebase:manage"],
          menu: {
            name: "知识库",
            group: "content",
            icon: markRaw(IconBookRead),
            priority: 30,
          },
        },
      },
    },
    {
      parentName: "Root",
      route: {
        path: "/minidocs/:name",
        name: "KnowledgeBaseDetail",
        component: KnowledgeBaseDetail,
        meta: {
          title: "知识库详情",
          permissions: ["plugin:halo-plugin-minidocs:knowledgebase:manage"],
        },
      },
    },
  ],
});
