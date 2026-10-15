/** Context-relative paths for deployments such as `/att/ui/` or a prefixed proxy path. */
export function contextRelativeReturnTo(pathname: string, search = '', hash = '', baseURI = document.baseURI): string {
  const contextRoot = new URL('../', baseURI).pathname;
  if (!pathname.startsWith(contextRoot)) return '/ui/';
  const route = pathname.slice(contextRoot.length);
  return '/' + route + search + hash;
}
