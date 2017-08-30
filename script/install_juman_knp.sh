#!/bin/sh
set -ex

apt-get update
apt-get install -y g++ libboost-dev make zlib1g-dev

cd download/jumanpp-1.02
./configure && make && make install

cd ../juman-7.01
./configure && make && make install

cd ../knp-4.17
./configure && make && make install

ldconfig
echo "knpとjumanppを組み合わせる" | jumanpp | knp
