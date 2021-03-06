import {RealEstateDeveloper} from './realestatedeveloper';

export interface Program {
  id: number;
  developer: RealEstateDeveloper;
  programName: string;
  programNumber: string;
  postalCode: string;
  address: string;
  url: string;
  imgUrl: string;
  pdfUrl: string;
  latitude: string;
  longitude: string;
  createdAt: string;
  modifiedAt: string;
}
