# usage: build.sh <outdir> <cxx> <extra flags...>
OUT=$1; CXX=$2; shift 2
mkdir -p $OUT/obj; cd /workspace/zt
for s in $(cat /workspace/ztbuild/srcs.txt); do echo $s; done | xargs -P8 -I{} sh -c "o=$OUT/obj/\$(echo {} | tr / _).o; [ -f \$o ] || $CXX -c -fPIC $(cat /workspace/ztbuild/flags.txt) -include cstring $* {} -o \$o || echo FAIL {}"
$CXX -shared $OUT/obj/*.o -o $OUT/libZeroTierOneJNI.so $LDX
