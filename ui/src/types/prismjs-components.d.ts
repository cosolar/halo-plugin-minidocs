// prismjs 语言组件只有部分被 @types/prismjs 覆盖（cpp/scala 等缺失），
// 通配声明让所有 prismjs/components/* 模块可用；已有具体声明依然优先。
declare module "prismjs/components/*";
