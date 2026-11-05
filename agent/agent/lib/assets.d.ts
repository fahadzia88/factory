// `?raw` imports embed a file as UTF-8 text (eve asset imports).
declare module "*?raw" {
  const content: string;
  export default content;
}
