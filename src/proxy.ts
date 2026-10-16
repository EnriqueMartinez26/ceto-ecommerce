import { NextResponse, type NextRequest } from "next/server";

export function proxy(request: NextRequest) {
  const slug = request.nextUrl.pathname.slice("/productos/".length);
  if (slug.length > 160 || !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(slug)) {
    return NextResponse.rewrite(new URL("/_not-found", request.url));
  }
  return NextResponse.next();
}

export const config = {
  matcher: "/productos/:slug",
};
