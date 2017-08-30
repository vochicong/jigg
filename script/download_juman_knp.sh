#!/bin/sh
set -ex

mkdir -p download
cd download

curl -SLO http://nlp.ist.i.kyoto-u.ac.jp/nl-resource/juman/juman-7.01.tar.bz2
curl -SLO http://lotus.kuee.kyoto-u.ac.jp/nl-resource/jumanpp/jumanpp-1.02.tar.xz
curl -SLO http://nlp.ist.i.kyoto-u.ac.jp/nl-resource/knp/knp-4.17.tar.bz2

tar xJ -f jumanpp-1.02.tar.xz
tar xj -f juman-7.01.tar.bz2
tar xj -f knp-4.17.tar.bz2
